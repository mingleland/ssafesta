package com.example.ssafesta.common;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the container's ERROR dispatch in the one error shape (docs/08 §1.3).
 *
 * <p>Boot's {@code BasicErrorController} renders the Whitelabel HTML page there, and that page is
 * what a client gets for every failure that ends <b>outside</b> a controller: an exception thrown
 * by a servlet filter, a {@code sendError}, a 404 on a permitted path. Neither
 * {@code @RestControllerAdvice} nor {@link ApiErrorWriter} covers it — the advice needs a chosen
 * handler, and the writer is only reached from the entry points Security is configured with. This
 * is the third route, and it was the one still leaving the envelope (T-145 계열).
 *
 * <p>Concretely: an unregistered OAuth provider makes Spring Security's authorization-request
 * resolver throw {@code InvalidClientRegistrationIdException}, an {@code IllegalArgumentException}
 * that no filter catches. It surfaced as Whitelabel HTML for a browser with a session and as a
 * misleading {@code UNAUTHORIZED} for everyone else, because the ERROR dispatch was itself being
 * refused by the chain.
 *
 * <p>The original exception is gone by the time this runs, so the status is all there is to answer
 * from. That is deliberate rather than a shortfall — nothing internal reaches the client (T-24),
 * and the failure is already logged where it was raised.
 */
@Hidden // 컨테이너가 부르는 자리다. 공개 API 가 아니므로 OpenApiDocumentationTest 의 대상도 아니다.
@RestController
class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ApiErrorResponse> handle(HttpServletRequest request) {
        // The code's status, never the dispatched one — the client branches on the code, and
        // GlobalExceptionHandler resolves the same way for the same reason.
        ErrorCode code = ErrorCode.of(dispatchedStatus(request));
        return ResponseEntity.status(code.status()).body(
                ApiErrorResponse.of(code, code.defaultMessage(), RequestIdFilter.current()));
    }

    /** Absent or unresolvable means we do not know what failed, which is a 500 and not a 400. */
    private HttpStatus dispatchedStatus(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = (attribute instanceof Integer value) ? HttpStatus.resolve(value) : null;
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }
}
