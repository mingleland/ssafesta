package com.example.ssafesta.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns every exception that escapes a controller into the one error shape (docs/08 §1.3).
 *
 * <p>Two rules hold everywhere here:
 *
 * <ul>
 *   <li><b>Nothing internal reaches the client.</b> Stack traces, exception class names and SQL stay
 *       in the log; the body carries a code and a sentence a user can read.
 *   <li><b>Unknown failures are logged loudly.</b> A 500 that nobody notices is how a silent
 *       fallback starts (T-24).
 * </ul>
 *
 * <p>Authentication failures raised <i>inside the security filter chain</i> do not pass through
 * here at all — {@code ApiErrorWriter} handles those from the configured entry point.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApi(ApiException exception, HttpServletRequest request) {
        ErrorCode code = exception.errorCode();
        if (request.getRequestURI().startsWith("/internal/")) {
            // 내부 API 의 거부는 사람이 보낸 요청이 아니라 다른 서비스와의 계약 위반이다. DEBUG 로 두면
            // 운영에서 보이지 않는다 — AI finalize 의 projectFacts 거부가 UNEXPECTED_ERROR 로만 남았던
            // 이유다 (S15P21A604-939). 본문·값은 남기지 않고 코드·필드·경로만 남긴다.
            log.warn("internal API 거부 — code={} path={} fields={} requestId={}", code,
                    request.getRequestURI(),
                    exception.errors().stream().map(ApiErrorDetail::field).toList(),
                    RequestIdFilter.current());
        } else {
            log.debug("거부 — code={} message={}", code, exception.getMessage());
        }
        return ResponseEntity.status(code.status()).body(ApiErrorResponse.of(
                code, exception.getMessage(), RequestIdFilter.current(),
                exception.errors(), exception.warnings(), exception.balance()));
    }

    /** A body that could not be parsed at all — malformed JSON, wrong type in a field. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.debug("요청 본문을 읽을 수 없습니다.", exception);
        return badRequest("요청 본문을 읽을 수 없습니다.");
    }

    /**
     * Bean Validation failures.
     *
     * <p>Every one of them reports the same rule, {@code FIELD_INVALID}, and carries the offending
     * field name in {@code field} (docs/08 §1.3-1). The field name used to go into {@code rule}
     * itself, which made the rule vocabulary grow with every DTO field and broke the client's
     * whitelist branch (#58).
     *
     * <p>A constraint's default message is English ("must not be blank"), so any annotation we add
     * has to carry its own Korean {@code message}. Until one does, the client gets a Korean fallback
     * rather than the framework's text.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleBeanValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                RequestIdFilter.current(),
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> ApiErrorDetail.field(error.getField(),
                                koreanOrFallback(error.getDefaultMessage())))
                        .toList(),
                null));
    }

    /**
     * A path variable or query parameter that could not be converted to its declared type —
     * {@code /surveys/abc/responses} against {@code @PathVariable Long surveyId}.
     *
     * <p>Needed explicitly because {@link #handleUnexpected}'s {@link ErrorResponse} branch does not
     * catch this one, so every typed parameter in the application answered a URL typo with a
     * <b>500</b> and an ERROR line in the log. That is the shape of a server fault, and it made a
     * client mistake look like ours (found while building the minigame result endpoint,
     * S15P21A604-502).
     *
     * <p>Reported as {@code VALIDATION_FAILED} with the parameter in {@code field}, the same
     * envelope Bean Validation produces — a client branches on one rule, not two.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String name = exception.getName();
        log.debug("경로·질의 파라미터 형식 오류 — name={} value={}", name, exception.getValue());
        // The offending value is not echoed: it is attacker-controlled text and this body is
        // rendered by clients. The parameter name stays out of the message for the same reason
        // messagesCarryNoDebuggingTail exists — message is for the reader, field is for the client.
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED, "요청 값의 형식이 올바르지 않습니다.",
                RequestIdFilter.current(),
                List.of(ApiErrorDetail.field(name, "값의 형식이 올바르지 않습니다.")), null));
    }

    /** Anything without a Hangul character is a framework default and must not reach the client. */
    private String koreanOrFallback(String message) {
        if (message == null || message.chars().noneMatch(GlobalExceptionHandler::isHangul)) {
            return "값이 올바르지 않습니다.";
        }
        return message;
    }

    private static boolean isHangul(int codePoint) {
        return codePoint >= 0xAC00 && codePoint <= 0xD7A3;
    }

    /**
     * Method-level security throws these <i>inside</i> the controller invocation, so unlike the
     * filter-chain case they do land here. Without this handler they would be swallowed by
     * {@link #handleUnexpected} and reported as 500 — an authorization decision disguised as a bug.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status()).body(ApiErrorResponse.of(
                ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), RequestIdFilter.current()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception) {
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.status()).body(ApiErrorResponse.of(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage(), RequestIdFilter.current()));
    }

    /**
     * Spring's own rejections carry their status on {@link ErrorResponse}, not by extending
     * {@code ResponseStatusException} — matching on that class missed every one of them (#113).
     * Only 4xx is answered quietly; a 5xx here is still an unknown failure (T-24).
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse rejection && rejection.getStatusCode().is4xxClientError()) {
            ErrorCode code = ErrorCode.of(HttpStatus.resolve(rejection.getStatusCode().value()));
            log.debug("프레임워크 거부 — status={} reason={}", rejection.getStatusCode(), exception.getMessage());
            // The code's status, never the exception's — the client branches on the code.
            return ResponseEntity.status(code.status())
                    .body(ApiErrorResponse.of(code, code.defaultMessage(), RequestIdFilter.current()));
        }
        log.error("처리되지 않은 예외 — requestId={}", RequestIdFilter.current(), exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status()).body(ApiErrorResponse.of(
                ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(),
                RequestIdFilter.current()));
    }

    private ResponseEntity<ApiErrorResponse> badRequest(String message) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED, message == null ? ErrorCode.VALIDATION_FAILED.defaultMessage() : message,
                RequestIdFilter.current()));
    }
}
