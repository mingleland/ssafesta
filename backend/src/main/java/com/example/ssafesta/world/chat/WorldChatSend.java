package com.example.ssafesta.world.chat;

/**
 * 클라이언트가 보내는 것 (S15P21A604-687).
 *
 * <p><b>내용 하나뿐이다.</b> 보낸 사람도 시각도 받지 않는다 — 클라이언트가 정할 수 있는 값이면
 * 사칭과 조작이 된다. 요청 JSON 에 {@code nickname} 이 실려 와도 이 record 에 자리가 없어
 * 그대로 버려진다.
 */
public record WorldChatSend(String content) { }
