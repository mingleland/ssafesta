package com.example.ssafesta.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 위임 요청이 실제 소켓 위에서 어떤 모양으로 나가는지 고정한다.
 *
 * <p>{@code HttpDocumentProcessingClientTest} 는 {@code MockRestServiceServer} 가 요청을 가로채므로
 * 여기서 고치는 결함을 볼 수 없다 — 그 가짜는 {@link AiProcessingConfiguration#requestFactory()} 를
 * 아예 지나지 않는다. GitLab #161 이 스테이징에서야 드러난 이유가 그것이라, 이 테스트만 JDK 의
 * {@code HttpServer} 로 진짜 연결을 받는다.
 */
class AiProcessingConfigurationTest {

    private HttpServer server;
    private final AtomicReference<String> upgradeHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ai/v1/documents/process", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * {@code Upgrade: h2c} 가 붙으면 uvicorn 이 본문을 잃는다. 헤더가 없는 것과 본문이 온전한 것을
     * 함께 단정하는 이유는, 헤더만 보면 왜 그것이 문제인지가 테스트에 남지 않기 때문이다.
     */
    @Test
    void theDelegationRequestNegotiatesNothingAndKeepsItsBody() {
        String body = "{\"jobId\":1,\"attemptNo\":1}";

        RestClient.builder()
                .baseUrl("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort())
                .requestFactory(AiProcessingConfiguration.requestFactory())
                .build()
                .post()
                .uri("/ai/v1/documents/process")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        assertNull(upgradeHeader.get(), "위임 요청에 Upgrade 헤더가 붙었다 — uvicorn 이 본문을 잃는다");
        assertEquals(body, receivedBody.get());
    }
}
