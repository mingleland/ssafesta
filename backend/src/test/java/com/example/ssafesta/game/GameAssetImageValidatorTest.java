package com.example.ssafesta.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

/**
 * What the server will and will not store (contract §5).
 *
 * <p>Every case here is one that passes a naive check. A file whose header says PNG, an animation
 * whose first frame is small, an SVG that is technically an image — each looks acceptable until the
 * browser tries to draw it or the process tries to decode it.
 */
class GameAssetImageValidatorTest {

    private final GameAssetImageValidator validator = new GameAssetImageValidator();

    /**
     * The WebP reader has to actually be installed.
     *
     * <p>It arrives through a ServiceLoader plugin, so nothing in our code references it and its
     * absence compiles, deploys and starts up fine. The only symptom would be "every valid WebP is
     * reported as corrupt", which reads like a bad file rather than a missing dependency — so the
     * dependency is asserted directly.
     */
    @Test
    void aWebpReaderIsRegistered() {
        Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReadersByMIMEType("image/webp");

        assertTrue(readers.hasNext(),
                "imageio-webp 가 ServiceLoader 에 등록되지 않았다 — 정상 WebP 가 전부 손상으로 거부된다");
    }

    /**
     * A real WebP all the way through — sniffed, measured and decoded.
     *
     * <p>{@link #aWebpReaderIsRegistered} says the plugin is on the classpath; this says the format
     * survives our own path. The two fail differently: a missing plugin throws
     * {@code IllegalStateException} out of {@code readerFor}, while a WebP that the sniffer or the
     * animation check mishandles comes back as an ordinary refusal.
     *
     * <p>Written as bytes because <b>there is no WebP writer</b> — the library reads only, so the
     * usual {@code ImageIO.write} trick the other helpers use is unavailable here. This is the
     * canonical 34-byte lossless (VP8L) 1×1, which exercises the RIFF/WEBP sniff, the {@code VP8X}
     * animation check on a file that has no {@code VP8X} chunk, and a real decode.
     */
    @Test
    void aStillWebpIsSniffedMeasuredAndDecoded() {
        byte[] webp = HexFormat.of().parseHex(
                "524946461A000000574542505650384C0D0000002F00000010071011118888FE0700");

        GameAssetImageValidator.VerifiedImage verified = validator.verify(webp, webp.length);

        assertEquals("image/webp", verified.contentType());
        assertEquals(1, verified.width());
        assertEquals(1, verified.height());
    }

    @Test
    void aPlainPngIsAcceptedWithTheDimensionsItActuallyHas() {
        byte[] png = png(64, 32);

        GameAssetImageValidator.VerifiedImage verified = validator.verify(png, png.length);

        assertEquals("image/png", verified.contentType());
        assertEquals(64, verified.width());
        assertEquals(32, verified.height());
        assertEquals(png.length, verified.byteSize());
        assertEquals(64, verified.sha256().length());
    }

    /**
     * JPEG, the other still format the contract accepts and the one the sniffer reads shortest.
     *
     * <p>Three magic bytes, against PNG's eight — so a JPEG is the format most likely to be missed
     * by a signature check that is subtly wrong, and nothing else here would notice.
     */
    @Test
    void aPlainJpegIsAcceptedAsAJpeg() {
        byte[] jpeg = jpeg(48, 24);

        GameAssetImageValidator.VerifiedImage verified = validator.verify(jpeg, jpeg.length);

        assertEquals("image/jpeg", verified.contentType());
        assertEquals(48, verified.width());
        assertEquals(24, verified.height());
    }

    /**
     * Size is checked before anything is decoded, so nothing has to hold the file to refuse it.
     *
     * <p>The bytes here are not an image at all, which is the point: if this passed the size gate it
     * would come back {@code DECODE_FAILED}, and the uploader would be told their 6 MB photo is
     * corrupt rather than too big.
     */
    @Test
    void aFileOverTheSizeLimitIsRefusedBeforeItIsDecoded() {
        assertRefusal(new byte[(int) GameAssetImageValidator.MAX_BYTES + 1],
                ErrorCode.GAME_ASSET_TOO_LARGE, "SIZE_EXCEEDED");
    }

    /**
     * The declared type is not consulted at all — the bytes decide.
     *
     * <p>This is the disguise case: a real GIF uploaded as {@code image/png} is stored as a GIF,
     * because what {@code /content} later hands the browser has to match what the browser will see.
     */
    @Test
    void theTypeComesFromTheBytesNotFromTheDeclaration() {
        byte[] gif = gif(8, 8, 1);

        assertEquals("image/gif", validator.verify(gif, gif.length).contentType());
    }

    /** An animation is allowed, and the recorded size is the first frame's. */
    @Test
    void anAnimatedGifWithinBudgetIsAccepted() {
        byte[] animated = gif(40, 20, 4);

        GameAssetImageValidator.VerifiedImage verified = validator.verify(animated, animated.length);

        assertEquals("image/gif", verified.contentType());
        assertEquals(40, verified.width());
        assertEquals(20, verified.height());
    }

    /**
     * The case a first-frame-only check waves through.
     *
     * <p>Seventeen 1000×1000 frames is 17,000,000 decoded pixels against a 16,777,216 budget, in a
     * file of a few kilobytes. Each frame on its own is unremarkable, which is exactly why the limit
     * has to apply to the file (⑦-a).
     */
    @Test
    void anAnimationWhoseFramesSumPastTheBudgetIsRefused() {
        byte[] animated = gif(1000, 1000, 17);

        assertRefusal(animated, ErrorCode.GAME_ASSET_DIMENSION_EXCEEDED, "PIXEL_BUDGET_EXCEEDED");
    }

    @Test
    void aSingleFrameWiderThanTheLimitIsRefused() {
        byte[] wide = png(5000, 8);

        assertRefusal(wide, ErrorCode.GAME_ASSET_DIMENSION_EXCEEDED, "DIMENSION_EXCEEDED");
    }

    /**
     * Animated WebP, refused on the header.
     *
     * <p>Not because animation is unwanted — animated GIF is accepted above — but because the WebP
     * reader we ship reads still images only, so its frames could not be checked. The refusal reads
     * the {@code VP8X} animation flag out of the RIFF container, which needs no decoder at all.
     */
    @Test
    void anAnimatedWebpIsRefusedOnItsHeader() {
        assertRefusal(animatedWebpHeader(), ErrorCode.GAME_ASSET_TYPE_UNSUPPORTED,
                "ANIMATED_WEBP_UNSUPPORTED");
    }

    /** SVG can carry script, so it is not an upload format however much it is an image format. */
    @Test
    void anSvgIsRefused() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertRefusal(svg, ErrorCode.GAME_ASSET_TYPE_UNSUPPORTED, "MIME_NOT_ALLOWED");
    }

    /**
     * A PNG signature followed by nothing that decodes.
     *
     * <p>Magic bytes alone would pass this. The refusal comes from the decode, which is why the
     * decode is not skipped once the signature matches.
     */
    @Test
    void aFileThatOnlyLooksLikeAPngIsRefused() {
        byte[] disguised = new byte[64];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, disguised, 0, signature.length);

        assertRefusal(disguised, ErrorCode.GAME_ASSET_CORRUPTED, "DECODE_FAILED");
    }

    /** The bytes that arrived are not the bytes the grant was issued for. */
    @Test
    void aSizeThatDisagreesWithTheDeclarationIsRefused() {
        byte[] png = png(8, 8);

        ApiException refusal = assertThrows(ApiException.class, () -> validator.verify(png, png.length + 1));

        assertEquals(ErrorCode.GAME_ASSET_CORRUPTED, refusal.errorCode());
        assertEquals("DECLARED_SIZE_MISMATCH", refusal.errors().get(0).rule());
    }

    @Test
    void anEmptyBodyIsRefused() {
        assertRefusal(new byte[0], ErrorCode.GAME_ASSET_CORRUPTED, "EMPTY_CONTENT");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void assertRefusal(byte[] content, ErrorCode expectedCode, String expectedRule) {
        ApiException refusal = assertThrows(ApiException.class,
                () -> validator.verify(content, content.length));

        assertEquals(expectedCode, refusal.errorCode());
        assertEquals(expectedRule, refusal.errors().stream().findFirst()
                .map(ApiErrorDetail::rule).orElse(null));
    }

    private byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] jpeg(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Solid colour, so many large frames still compress to a few kilobytes. */
    private byte[] gif(int width, int height, int frames) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.prepareWriteSequence(null);
                BufferedImage frame = canvas(width, height, BufferedImage.TYPE_BYTE_INDEXED);
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        javax.imageio.ImageTypeSpecifier.createFromRenderedImage(frame), null);
                for (int i = 0; i < frames; i++) {
                    writer.writeToSequence(new IIOImage(frame, null, metadata), null);
                }
                writer.endWriteSequence();
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private BufferedImage canvas(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x2F, 0x6F, 0xDF));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    /**
     * A RIFF/WEBP container whose {@code VP8X} flags byte has the animation bit set.
     *
     * <p>Hand-built rather than encoded: the refusal happens before any decoder is asked, so the
     * header is the whole input the code under test looks at. Producing a real animated WebP would
     * need the very encoder we do not have.
     */
    private byte[] animatedWebpHeader() {
        byte[] header = new byte[32];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 4);
        header[4] = 24;
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
        System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, header, 12, 4);
        header[16] = 10;
        header[20] = 0x02; // ANIMATION
        return header;
    }
}
