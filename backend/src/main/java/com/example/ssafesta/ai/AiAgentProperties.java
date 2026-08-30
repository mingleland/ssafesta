package com.example.ssafesta.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The three AI-staff caps spec 007 requires to be settings rather than constants (C-13, FR-018).
 *
 * @param perBoothLimit       AI staff per booth. <b>Must be 1</b> — see below
 * @param documentCountLimit  documents per agent (FR-018). Consumed by S15P21A604-106, not here
 * @param documentTotalBytes  total original size per agent (FR-018). Same
 */
@ConfigurationProperties("app.agent")
public record AiAgentProperties(int perBoothLimit, int documentCountLimit,
                                DataSize documentTotalBytes) {

    public AiAgentProperties {
        // C-13 says the number is a setting, and V15 says the database allows exactly one row per
        // booth. Two places that can disagree, so refuse to start when they do: a deployment that
        // set 2 would otherwise pass the service's own check and then take a 500 from the index.
        // Raising the limit means dropping ux_ai_agents_booth in the same change.
        if (perBoothLimit != 1) {
            throw new IllegalStateException(
                    "app.agent.per-booth-limit 은 1 이어야 합니다 (V15 ux_ai_agents_booth 와 함께 바꿔야 합니다): "
                            + perBoothLimit);
        }
        if (documentCountLimit < 1) {
            throw new IllegalStateException(
                    "app.agent.document-count-limit 은 1 이상이어야 합니다: " + documentCountLimit);
        }
        // null 을 먼저 본다 — 설정이 빠지면 DataSize 가 null 로 오고 toBytes() 는 NPE 가 된다.
        if (documentTotalBytes == null || documentTotalBytes.toBytes() < 1) {
            throw new IllegalStateException(
                    "app.agent.document-total-bytes 는 1 이상이어야 합니다: " + documentTotalBytes);
        }
    }
}
