package com.example.ssafesta.world.chat;

import java.time.Instant;

/**
 * 월드 채팅 토픽의 시스템 입장 알림이다.
 *
 * <p>일반 채팅과 달리 사용자가 보낸 본문이 없다. {@code type}을 명시해 클라이언트가 이름과
 * 문장을 한 줄의 채팅으로 오인하지 않고 별도 알림으로 그리게 한다.
 */
public record WorldChatJoinNotice(String type, String nickname, Instant sentAt) {

    static final String TYPE = "JOIN";

    static WorldChatJoinNotice of(String nickname, Instant sentAt) {
        return new WorldChatJoinNotice(TYPE, nickname, sentAt);
    }
}
