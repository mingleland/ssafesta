package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Every error leaves this API in one shape (spec 005 research R-09, docs/08 §1.3).
 *
 * <p>The point of these tests is coverage of the <b>paths</b>, not of any one endpoint: a controller
 * throw, a Spring rejection, and a Security filter rejection each reach the client by a different
 * route, and before spec 005 the last of those answered in a different shape from the others.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ErrorEnvelopeIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void anUnauthenticatedRequestIsRefusedInTheEnvelope() throws Exception {
        // Raised inside the Security filter chain, which never reaches @RestControllerAdvice.
        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void aRejectedTokenIsRefusedInTheEnvelope() throws Exception {
        // The resource server installs its own entry point; without an override this answered
        // with an empty body and only a WWW-Authenticate header.
        mockMvc.perform(get("/api/v1/wallets/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void aMissingBoothCarriesItsCode() throws Exception {
        mockMvc.perform(get("/api/v1/booths/{id}", 9_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOTH_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void anUnknownPathIsStillAnEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-endpoint"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").isString());
    }

    @Test
    void theRequestIdInTheBodyMatchesTheHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/booths/{id}", 9_999_999L))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andReturn();

        String headerValue = result.getResponse().getHeader(RequestIdFilter.HEADER);
        String body = result.getResponse().getContentAsString();

        assertNotNull(headerValue, "X-Request-Id 헤더가 있어야 합니다.");
        assertTrue(headerValue.startsWith("req_"), "요청 식별자 접두사가 있어야 합니다: " + headerValue);
        assertTrue(body.contains("\"requestId\":\"" + headerValue + "\""),
                "본문의 requestId와 헤더가 같아야 로그를 찾을 수 있습니다. header=" + headerValue + " body=" + body);
    }

    @Test
    void twoRequestsGetDifferentIds() throws Exception {
        String first = idOf(mockMvc.perform(get("/api/v1/booths/{id}", 9_999_999L)).andReturn());
        String second = idOf(mockMvc.perform(get("/api/v1/booths/{id}", 9_999_999L)).andReturn());

        assertFalse(first.equals(second), "요청마다 다른 식별자여야 합니다.");
    }

    @Test
    void anErrorBodyNeverLeaksInternals() throws Exception {
        String body = mockMvc.perform(post("/api/v1/booth-slots/{slotId}/leases", 9_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationDays\":1}"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("Exception"), "예외 클래스명이 노출되면 안 됩니다: " + body);
        assertFalse(body.contains("com.example.ssafesta"), "패키지 경로가 노출되면 안 됩니다: " + body);
        assertFalse(body.contains("\tat "), "스택트레이스가 노출되면 안 됩니다: " + body);
    }

    @Test
    void everyErrorCodeHasAStatusAndAMessage() {
        for (ErrorCode code : ErrorCode.values()) {
            assertNotNull(code.status(), code + "에 HTTP status가 없습니다.");
            assertNotNull(code.defaultMessage(), code + "에 기본 메시지가 없습니다.");
            assertFalse(code.defaultMessage().isBlank(), code + "의 기본 메시지가 비어 있습니다.");
        }
    }

    @Test
    void validationDetailsRideInTheSameEnvelope() {
        // FR-016: the layout validator will fill these. The shape is asserted here so it cannot
        // drift while the endpoints that use it are still being written.
        ApiErrorResponse response = ApiErrorResponse.of(ErrorCode.LAYOUT_VALIDATION_FAILED, "배치를 공개할 수 없습니다.",
                "req_test", java.util.List.of(ApiErrorDetail.of("OBJECT_LIMIT", "오브젝트는 12개까지입니다.")),
                java.util.List.of(ApiErrorDetail.of("CONFIG_NOT_LINKED", "ai-1", "AI 직원이 연결되지 않았습니다.")));

        assertEquals("LAYOUT_VALIDATION_FAILED", response.code());
        assertEquals(1, response.errors().size());
        assertEquals("ai-1", response.warnings().get(0).objectId());
        assertEquals(null, ApiErrorResponse.of(ErrorCode.BOOTH_NOT_FOUND, null, "req_test", java.util.List.of(), null)
                .errors(), "빈 목록은 직렬화에서 빠지도록 null이어야 합니다.");
    }

    private String idOf(MvcResult result) {
        return result.getResponse().getHeader(RequestIdFilter.HEADER);
    }
}
