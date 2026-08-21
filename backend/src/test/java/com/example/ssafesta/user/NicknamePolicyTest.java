package com.example.ssafesta.user;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NicknamePolicyTest {

    private final NicknamePolicy policy = new NicknamePolicy();

    @Test
    void allowsOrdinaryNickname() {
        assertDoesNotThrow(() -> policy.validate("싸피11기홍길동"));
    }

    @Test
    void blocksNormalizedProfanityAndReservedNames() {
        assertThrows(InvalidNicknameException.class, () -> policy.validate("씨---1---발"));
        assertThrows(InvalidNicknameException.class, () -> policy.validate("F_U_C_K"));
        assertThrows(InvalidNicknameException.class, () -> policy.validate("관리자"));
    }

    @Test
    void blocksContactPatterns() {
        assertThrows(InvalidNicknameException.class, () -> policy.validate("010-1234-5678"));
        assertThrows(InvalidNicknameException.class, () -> policy.validate("https://example.com"));
    }
}
