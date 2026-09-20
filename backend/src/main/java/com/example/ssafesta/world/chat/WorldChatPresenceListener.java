package com.example.ssafesta.world.chat;

import java.security.Principal;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

/** STOMP 인증 연결을 월드 채팅의 입장 시스템 알림으로 바꾼다. */
@Component
public class WorldChatPresenceListener {

    private final WorldChatService chat;

    public WorldChatPresenceListener(WorldChatService chat) {
        this.chat = chat;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Principal user = event.getUser();
        if (user == null) return;
        try {
            chat.announceJoin(Long.valueOf(user.getName()));
        } catch (NumberFormatException invalidPrincipal) {
            // 인터셉터가 넣은 Principal은 숫자 회원 id다. 그 계약이 깨졌다면 방송하지 않고 원인을 남긴다.
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("월드 채팅 입장 알림을 만들지 못했습니다 — 회원 id 형식이 아닙니다: {}", user.getName());
        }
    }
}
