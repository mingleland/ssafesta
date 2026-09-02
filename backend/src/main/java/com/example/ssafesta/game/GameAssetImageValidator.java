package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * Decides whether uploaded bytes are an image we will store (contract §5).
 *
 * <p>Pure on purpose: it takes a byte array and knows nothing about where the bytes came from or
 * where they will go. The upload flow puts them in Postgres today and may put them in R2 later;
 * neither changes what makes a file acceptable.
 *
 * <p>Nothing here repairs anything. A file that fails is refused with the rule that refused it —
 * re-encoding it, or storing it with corrected metadata, would make the stored bytes differ from
 * the bytes the user chose. That silent-substitution shape is what T-24 was.
 */
@Component
public class GameAssetImageValidator {

    /** Contract §5, taken from the FE's existing limit so a locally-accepted file cannot be refused here. */
    static final long MAX_BYTES = 5L * 1024 * 1024;

    static final int MAX_EDGE = 4096;

    /**
     * Total decoded pixels for the <b>whole file</b>, not one frame.
     *
     * <p>This is the contract's own 4096×4096 figure, applied to the file. §5 states the number is
     * there to stop decompression bombs, and per-frame it does not: a small animation with many
     * frames costs the browser the sum, and checking only the first frame lets a later frame exceed
     * the limit or be corrupt. Applying the existing ceiling to the file is the faithful reading —
     * inventing a separate frame budget would be a new product number, which is not ours to set
     * (헌법 30조). A 4096×4096 animation therefore allows one frame, which is the intended strictness.
     */
    static final long MAX_TOTAL_PIXELS = (long) MAX_EDGE * MAX_EDGE;

    /** Contract §5. SVG is absent deliberately — it can carry script, so we do not accept it as an upload. */
    static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    /**
     * @param content          the uploaded bytes, already bounded by the request size limit
     * @param declaredByteSize what the start request said the size would be
     */
    public VerifiedImage verify(byte[] content, long declaredByteSize) {
        if (content == null || content.length == 0) {
            throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "EMPTY_CONTENT", "이미지 파일이 비어 있습니다.");
        }
        if (content.length > MAX_BYTES) {
            throw refuse(ErrorCode.GAME_ASSET_TOO_LARGE, "SIZE_EXCEEDED",
                    "이미지는 " + (MAX_BYTES / 1024 / 1024) + "MB 이하만 올릴 수 있습니다.");
        }
        if (content.length != declaredByteSize) {
            // The declared value is not trusted for acceptance, but a mismatch means the object is
            // not the one the grant was issued for. Refusing is cheaper than storing an unexplained one.
            throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "DECLARED_SIZE_MISMATCH",
                    "업로드된 파일 크기가 요청한 크기와 다릅니다.");
        }

        String contentType = sniffContentType(content);
        if (contentType == null) {
            throw refuse(ErrorCode.GAME_ASSET_TYPE_UNSUPPORTED, "MIME_NOT_ALLOWED",
                    "PNG · JPEG · GIF · WebP 만 올릴 수 있습니다.");
        }
        if ("image/webp".equals(contentType) && isAnimatedWebp(content)) {
            // Rejected because we cannot inspect it, not because animation is unwanted: the WebP
            // reader we ship handles still images only, so its frames would go unchecked. Animated
            // GIF is allowed and fully checked below.
            throw refuse(ErrorCode.GAME_ASSET_TYPE_UNSUPPORTED, "ANIMATED_WEBP_UNSUPPORTED",
                    "애니메이션 WebP는 지원하지 않습니다. GIF로 변환하거나 정적 이미지를 올려 주세요.");
        }

        Dimensions dimensions = inspect(content, contentType);
        return new VerifiedImage(contentType, content.length, dimensions.width(), dimensions.height(),
                sha256Hex(content));
    }

    /**
     * Frame headers first, pixels second.
     *
     * <p>Reading dimensions through {@link ImageReader#getWidth} touches headers only, so a file
     * that busts the budget is refused before a pixel buffer exists. Decoding first and measuring
     * afterwards would allocate exactly what the limit is meant to prevent.
     */
    private Dimensions inspect(byte[] content, String contentType) {
        ImageReader reader = readerFor(contentType);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "DECODE_FAILED", "이미지를 읽을 수 없습니다.");
            }
            reader.setInput(input);

            int frames = frameCount(reader);
            int firstWidth = 0;
            int firstHeight = 0;
            long totalPixels = 0;

            for (int frame = 0; frame < frames; frame++) {
                int width;
                int height;
                try {
                    width = reader.getWidth(frame);
                    height = reader.getHeight(frame);
                } catch (IOException | RuntimeException exception) {
                    throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "DECODE_FAILED",
                            "이미지를 읽을 수 없습니다.");
                }
                if (width > MAX_EDGE || height > MAX_EDGE) {
                    throw refuse(ErrorCode.GAME_ASSET_DIMENSION_EXCEEDED, "DIMENSION_EXCEEDED",
                            "이미지 크기는 " + MAX_EDGE + "×" + MAX_EDGE + " 이하여야 합니다.");
                }
                long pixels = (long) width * height;
                // Compared before adding, so the sum itself cannot overflow.
                if (pixels > MAX_TOTAL_PIXELS - totalPixels) {
                    throw refuse(ErrorCode.GAME_ASSET_DIMENSION_EXCEEDED, "PIXEL_BUDGET_EXCEEDED",
                            "프레임 전체 픽셀 수가 " + MAX_EDGE + "×" + MAX_EDGE + " 예산을 넘습니다.");
                }
                totalPixels += pixels;
                if (frame == 0) {
                    firstWidth = width;
                    firstHeight = height;
                }
            }

            // Every frame, not just the first: a corrupt tail is exactly what a first-frame-only
            // check misses, and it surfaces later as a broken image at play time.
            for (int frame = 0; frame < frames; frame++) {
                boolean decoded;
                try {
                    decoded = reader.read(frame) != null;
                } catch (IOException | RuntimeException exception) {
                    decoded = false;
                }
                if (!decoded) {
                    throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "DECODE_FAILED",
                            "이미지를 읽을 수 없습니다.");
                }
            }

            return new Dimensions(firstWidth, firstHeight);
        } catch (IOException exception) {
            throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "DECODE_FAILED", "이미지를 읽을 수 없습니다.");
        } finally {
            reader.dispose();
        }
    }

    private int frameCount(ImageReader reader) {
        int frames;
        try {
            // allowSearch = true: GIF frame count is not in a header, it has to be walked. The walk
            // reads the bounded buffer we already hold and allocates no pixels.
            frames = reader.getNumImages(true);
        } catch (IOException | RuntimeException exception) {
            throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "DECODE_FAILED", "이미지를 읽을 수 없습니다.");
        }
        if (frames < 1) {
            throw refuse(ErrorCode.GAME_ASSET_CORRUPTED, "NO_FRAMES", "이미지 프레임이 없습니다.");
        }
        return frames;
    }

    /**
     * A missing reader is a deployment fault, not a bad upload.
     *
     * <p>WebP support arrives through a ServiceLoader plugin, so losing that dependency shows up as
     * "every valid WebP is corrupt" unless it is separated from the corruption path here.
     */
    private ImageReader readerFor(String contentType) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(contentType);
        if (!readers.hasNext()) {
            throw new IllegalStateException(
                    "ImageIO reader 가 없다: " + contentType + " — imageio-webp 의존성을 확인하라");
        }
        return readers.next();
    }

    /**
     * The declared type never decides. Magic bytes do (contract §5 — 확장자·선언 MIME 위장 차단).
     *
     * @return the content type the bytes actually are, or {@code null} when it is not one we accept
     */
    private String sniffContentType(byte[] content) {
        if (startsWith(content, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 'G', 'I', 'F', '8') && content.length > 5
                && (content[4] == '7' || content[4] == '9') && content[5] == 'a') {
            return "image/gif";
        }
        if (startsWith(content, 'R', 'I', 'F', 'F') && content.length >= 12
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * WebP is RIFF, so animation is visible in the header — no decoder involved.
     *
     * <p>Two signals, either is enough: the {@code VP8X} extended-format flags byte carries an
     * animation bit, and an animated file also has an {@code ANIM} chunk. Checking both means a
     * writer that sets one without the other does not slip through.
     */
    private boolean isAnimatedWebp(byte[] content) {
        if (content.length >= 21
                && content[12] == 'V' && content[13] == 'P' && content[14] == '8' && content[15] == 'X'
                && (content[20] & 0x02) != 0) {
            return true;
        }
        int limit = Math.min(content.length - 4, 4096);
        for (int index = 12; index < limit; index++) {
            if (content[index] == 'A' && content[index + 1] == 'N'
                    && content[index + 2] == 'I' && content[index + 3] == 'M') {
                return true;
            }
        }
        return false;
    }

    private boolean startsWith(byte[] content, int... expected) {
        if (content.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((content[index] & 0xFF) != (expected[index] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", exception);
        }
    }

    /** {@code rule} carries which check refused it — contract §6 puts the specific violation there, not in a new code. */
    private ApiException refuse(ErrorCode code, String rule, String message) {
        return new ApiException(code, message, List.of(ApiErrorDetail.of(rule, message)), null);
    }

    public record VerifiedImage(String contentType, long byteSize, int width, int height, String sha256) { }

    private record Dimensions(int width, int height) { }
}
