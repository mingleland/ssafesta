package com.example.ssafesta.world.chat;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/** 3초 안에 두 번째다 (S15P21A604-687). */
public class WorldChatTooFastException extends ApiException {

    WorldChatTooFastException() {
        super(ErrorCode.CHAT_TOO_FAST);
    }
}
