package com.example.ssafesta.common;

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
    ResponseEntity<ApiErrorResponse> handleApi(ApiException exception) {
        ErrorCode code = exception.errorCode();
        log.debug("거부 — code={} message={}", code, exception.getMessage());
        return ResponseEntity.status(code.status()).body(ApiErrorResponse.of(
                code, exception.getMessage(), RequestIdFilter.current(),
                exception.errors(), exception.warnings()));
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
     * The funnel everything unhandled reaches — which makes it the only place Spring's own
     * rejections can be caught.
     *
     * <p>They carry their status on the {@link ErrorResponse} <i>interface</i>. Only the hand-thrown
     * {@code ResponseStatusException} extends a class an {@code @ExceptionHandler} could name; the
     * framework's own — a missing handler (404), an unsupported method (405), a missing cookie (400)
     * — extend {@code ServletException} instead. Matching on {@code ResponseStatusException} missed
     * every one of them, so client mistakes were reported as server faults, and the
     * {@code METHOD_NOT_ALLOWED} arm of {@link #codeFor} was unreachable (#113).
     *
     * <p>Only 4xx is answered quietly. A 5xx arriving here is still an unknown failure and is still
     * logged loudly — a 500 that nobody notices is how a silent fallback starts (T-24).
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse rejection && rejection.getStatusCode().is4xxClientError()) {
            ErrorCode code = codeFor(HttpStatus.resolve(rejection.getStatusCode().value()));
            if (code == null) {
                // A status the envelope has no code for. Borrowing another code would put one status
                // in the body and a different one on the wire, so the generic client-error code
                // answers — and says so, because a status we never contracted is worth seeing.
                log.warn("계약에 없는 프레임워크 거부입니다 — status={} 를 일반 코드로 답합니다.",
                        rejection.getStatusCode());
                code = ErrorCode.VALIDATION_FAILED;
            }
            // Spring writes its own reason in English ("No static resource api/v1/...", "Request
            // method 'PUT' is not supported"). Client-facing text is Korean only, so the reason goes
            // to the log and the client gets the code's message.
            log.debug("프레임워크 거부 — status={} reason={}", rejection.getStatusCode(), exception.getMessage());
            // The code's own status, never the exception's. ErrorCode declares a status per code and
            // the client branches on the code, so the two disagreeing makes ErrorCode.status() a lie:
            // a 415 answered with VALIDATION_FAILED said 400 in the body and 415 on the wire
            // (raised in review of !56).
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

    /** The code contracted for this status, or {@code null} when the envelope has none. */
    private ErrorCode codeFor(HttpStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case BAD_REQUEST -> ErrorCode.VALIDATION_FAILED;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case NOT_ACCEPTABLE -> ErrorCode.NOT_ACCEPTABLE;
            default -> null;
        };
    }
}
