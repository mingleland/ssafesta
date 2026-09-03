package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The namespace is the only thing separating dev from demo, so a bad value must not boot. */
class RedisKeyspacePropertiesTest {

    @Test
    void aMissingNamespaceIsRefused() {
        assertThrows(IllegalStateException.class, () -> new RedisKeyspaceProperties(null));
        assertThrows(IllegalStateException.class, () -> new RedisKeyspaceProperties(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {" dev", "dev ", "\tdev"})
    void surroundingWhitespaceIsRefusedRatherThanTrimmed(String namespace) {
        // Trimming would isolate the environments anyway, so the wrong config would never surface.
        assertThrows(IllegalStateException.class, () -> new RedisKeyspaceProperties(namespace));
    }

    @Test
    void aNamespaceCarryingTheSeparatorIsRefused() {
        // "dev:auth" would write into exactly where the "dev" environment's auth keys live.
        assertThrows(IllegalStateException.class, () -> new RedisKeyspaceProperties("dev:auth"));
    }

    @Test
    void anUnresolvedPlaceholderIsRefused() {
        // It passes every other check — non-empty, no surrounding whitespace, no ':' — so without
        // this branch a deployment missing FESTA_ENVIRONMENT boots with both environments sharing
        // the literal "${FESTA_ENVIRONMENT}" as their namespace, which is no isolation at all.
        assertThrows(
                IllegalStateException.class,
                () -> new RedisKeyspaceProperties("${FESTA_ENVIRONMENT}"));
    }

    @Test
    void thePrefixCarriesTheSeparator() {
        assertEquals("demo:", new RedisKeyspaceProperties("demo").prefix());
    }
}
