package com.example.ssafesta.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssafesta.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the browser is allowed to send (S15P21A604-909).
 *
 * <p>A request header the server requires but CORS does not list is not a slow path or a bad error
 * message — the request never leaves the browser. The coin adjustment endpoint demands
 * {@code Idempotency-Key}, which is custom enough to trigger a preflight, and the allow-list left it
 * out: every browser call failed while curl succeeded, so nothing in the test suite noticed.
 *
 * <p>The assertion is on the preflight rather than on a real adjustment because that is where the
 * refusal happens. MockMvc would happily serve the POST either way; only the {@code OPTIONS}
 * response tells us what a browser would do with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsPreflightIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Value("${app.auth.frontend-base-url}") private String trustedOrigin;

    @Test
    void preflightForCoinAdjustmentAllowsIdempotencyKey() throws Exception {
        mockMvc.perform(options("/api/v1/admin/wallets/12/adjustments")
                        .header("Origin", trustedOrigin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", trustedOrigin))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("Idempotency-Key")));
    }
}
