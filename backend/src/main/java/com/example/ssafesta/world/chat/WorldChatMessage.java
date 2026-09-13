package com.example.ssafesta.world.chat;

import java.time.Instant;

/**
 * 토픽으로 나가는 것 (S15P21A604-687).
 *
 * @param nickname <b>서버가 조회한 값</b>이다. 클라이언트가 보낸 이름을 실으면 사칭이 된다
 * @param content {@code strip()} 을 거친 정규화본. 보낸 그대로가 아니다
 */
public record WorldChatMessage(Long senderUserId, String nickname, String content, Instant sentAt) { }
