package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every domain exception carries its own {@link ErrorCode}.
 *
 * <p>This exists because the same defect has been fixed four times — 2026-08-20 twice, 08-27, and
 * S15P21A604-402 — and each fix repaired only the exception that had been reported. An exception
 * that does not extend {@link ApiException} cannot carry a code, so {@code GlobalExceptionHandler}
 * answers {@code 500 INTERNAL_ERROR} unless every single controller on the way out translates it by
 * hand. The moment a second caller appears without that translation, the refusal turns into a
 * server fault (T-113) — and a wrong <i>success</i>-shaped 500 is not something a test notices
 * unless it is looking.
 *
 * <p>T-127 recorded that {@code grep "extends RuntimeException"} found the whole family every time
 * and was simply not run. This is that grep, as a test, so the fifth recurrence fails the build
 * instead of reaching a user.
 *
 * <p>The check is on the source tree rather than the classpath on purpose: it is the same one line
 * a person would type, it needs no scanner API, and it reads as what it enforces. The cost is that
 * an exception class not named {@code *Exception} would be missed — the naming has held for every
 * one of them so far, and a scanner would not have caught any of the four regressions either.
 */
class DomainExceptionEnvelopeTest {

    /**
     * {@code ApiException} is the base itself. {@code LayoutParseException} carries no code
     * deliberately: four of its five throw sites parse layout JSON that we wrote ourselves through
     * {@code LayoutJson.write}, so a parse failure there means the stored row is corrupt. That is a
     * server fault, and 500 is the honest answer (the same judgement {@code ProjectService} spells
     * out for unnamed integrity violations). The fifth site — the request body — is translated at
     * its caller into a validation error.
     */
    private static final Set<String> WITHOUT_A_CODE_ON_PURPOSE = Set.of(
            "ApiException.java",
            "LayoutParseException.java");

    @Test
    void everyDomainRuntimeExceptionCarriesAnErrorCode() throws IOException {
        try (Stream<Path> sources = Files.walk(sourceRoot())) {
            List<String> offenders = sources
                    .filter(path -> path.getFileName().toString().endsWith("Exception.java"))
                    .filter(path -> !WITHOUT_A_CODE_ON_PURPOSE.contains(path.getFileName().toString()))
                    .filter(path -> !read(path).contains("extends ApiException"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertEquals(List.of(), offenders,
                    "이 예외들은 ErrorCode 를 싣지 못해 500 으로 나갑니다. ApiException 을 상속하게 하거나, "
                            + "500 이 정말 맞다면 이유와 함께 WITHOUT_A_CODE_ON_PURPOSE 에 넣으세요.");
        }
    }

    /** Surefire runs with the module directory as the working directory; the fallback is for a run started at the repository root. */
    private static Path sourceRoot() {
        Path fromModule = Path.of("src/main/java/com/example/ssafesta");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRepositoryRoot = Path.of("backend").resolve(fromModule);
        if (Files.isDirectory(fromRepositoryRoot)) {
            return fromRepositoryRoot;
        }
        throw new IllegalStateException("소스 트리를 찾지 못했습니다 — cwd=" + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
