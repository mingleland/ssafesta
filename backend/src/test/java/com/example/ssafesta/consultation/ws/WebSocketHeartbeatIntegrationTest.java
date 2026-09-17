package com.example.ssafesta.consultation.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.example.ssafesta.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.TaskScheduler;

/**
 * 유휴 realtime 연결을 살리는 simple broker heartbeat 설정을 Spring 컨텍스트에서 고정한다.
 *
 * <p>설정값만 테스트하면 scheduler가 broker에 실제로 연결되지 않아도 통과할 수 있다. 실행 중인
 * broker handler에서 두 값을 함께 읽어야 Cloudflare 유휴 종료 회귀를 막는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WebSocketHeartbeatIntegrationTest {

    @Autowired private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("messageBrokerTaskScheduler")
    private TaskScheduler heartbeatScheduler;

    @Test
    void simpleBrokerSendsAHeartbeatEveryTwentyFiveSeconds() {
        SimpleBrokerMessageHandler broker = applicationContext.getBean(
                "simpleBrokerMessageHandler", SimpleBrokerMessageHandler.class);

        assertArrayEquals(new long[] {25_000, 0}, broker.getHeartbeatValue(),
                "서버만 25초 heartbeat를 송신한다.");
        assertSame(heartbeatScheduler, broker.getTaskScheduler(),
                "heartbeat 값만 두고 scheduler를 연결하지 않으면 broker가 기동 중 실패한다.");
    }
}
