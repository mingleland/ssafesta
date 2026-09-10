package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.example.ssafesta.internal.ai.InternalTokenProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 계약 호출이 계약대로 나가는지, 그리고 202 가 아닌 답을 어떻게 다루는지.
 *
 * <p>소켓 없이 돈다 — {@code MockRestServiceServer} 가 요청을 가로채므로 FastAPI 도 컨테이너도
 * 필요 없다. 통합 테스트는 이 클래스를 가짜로 갈아끼우기 때문에, 경로·헤더·상태 분기를 실제로
 * 보는 곳은 여기뿐이다.
 */
class HttpDocumentProcessingClientTest {

    private static final DocumentProcessingClient.ProcessingRequest REQUEST =
            new DocumentProcessingClient.ProcessingRequest(7L, 0, 42L, 10L, 3L, "guide.pdf",
                    "application/pdf", 1024L, "R2", "festa-documents",
                    "booths/10/agents/3/documents/42/guide.pdf", "%064x".formatted(1));

    private MockRestServiceServer server;
    private DocumentProcessingClient client;

    private void given(HttpStatus answer) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        // 송신은 첫 값이다 — 둘째는 회전 중에만 존재한다.
        client = new HttpDocumentProcessingClient(builder,
                new InternalTokenProperties("inbound", "outbound-first,outbound-old", "infra"));
        server.expect(requestTo("http://ai.test:8000/ai/v1/documents/process"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer outbound-first"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").value(7))
                .andExpect(jsonPath("$.attemptNo").value(0))
                .andExpect(jsonPath("$.documentId").value(42))
                .andExpect(jsonPath("$.boothId").value(10))
                .andExpect(jsonPath("$.agentId").value(3))
                .andExpect(jsonPath("$.originalFilename").value("guide.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.fileSizeBytes").value(1024))
                .andExpect(jsonPath("$.storageProvider").value("R2"))
                .andExpect(jsonPath("$.storageBucket").value("festa-documents"))
                .andExpect(jsonPath("$.objectKey").exists())
                .andExpect(jsonPath("$.sourceHash").exists())
                .andRespond(withStatus(answer));
    }

    /** 계약의 12필드가 camelCase 이름 그대로 나간다 — {@code additionalProperties: false} 라 하나도 틀리면 안 된다. */
    @Test
    void theRequestCarriesTheContractFields() {
        given(HttpStatus.ACCEPTED);

        client.startProcessing(REQUEST);

        server.verify();
    }

    @Test
    void anAcceptedCallReturnsNormally() {
        given(HttpStatus.ACCEPTED);

        client.startProcessing(REQUEST);
    }

    /**
     * 202 가 아니면 실패다.
     *
     * <p>401 은 토큰 설정 잘못이고 422 는 계약 어긋남인데, 둘 다 <b>조용히 성공으로 넘기면</b>
     * Job 이 배달된 것으로 남아 배차기가 다시 보내지 않는다. 문서가 영구히 처리되지 않는다.
     */
    @Test
    void anUnauthorizedCallIsAFailure() {
        given(HttpStatus.UNAUTHORIZED);

        assertThrows(DocumentProcessingUnavailableException.class,
                () -> client.startProcessing(REQUEST));
    }

    @Test
    void anUnprocessableCallIsAFailure() {
        given(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThrows(DocumentProcessingUnavailableException.class,
                () -> client.startProcessing(REQUEST));
    }

    /** 계약은 202 다. 200 을 성공으로 받으면 계약이 바뀐 것을 아무도 모르게 된다. */
    @Test
    void aPlainOkIsAlsoAFailure() {
        given(HttpStatus.OK);

        assertThrows(DocumentProcessingUnavailableException.class,
                () -> client.startProcessing(REQUEST));
    }
}
