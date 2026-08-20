package com.example.ssafesta.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

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
        return ResponseEntity.status(code.status()).body(ApiErrorResponse.of(
                code, exception.getMessage(), RequestIdFilter.current(),
                exception.errors(), exception.warnings()));
    }

    /**
     * Spring's own rejections — a missing handler (404), an unsupported method (405) — plus any
     * remaining hand-thrown ones. They already know their status; all they lack is a code.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        ErrorCode code = codeFor(status);
        String message = exception.getReason() == null ? code.defaultMessage() : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiErrorResponse.of(code, message, RequestIdFilter.current()));
    }

    /** A body that could not be parsed at all — malformed JSON, wrong type in a field. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.debug("요청 본문을 읽을 수 없습니다.", exception);
        return badRequest("요청 본문을 읽을 수 없습니다.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleBeanValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                RequestIdFilter.current(),
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> ApiErrorDetail.of(error.getField(), error.getDefaultMessage()))
                        .toList(),
                null));
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

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
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

    private ErrorCode codeFor(HttpStatus status) {
        if (status == null) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (status) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case BAD_REQUEST -> ErrorCode.VALIDATION_FAILED;
            default -> status.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
    }
}
