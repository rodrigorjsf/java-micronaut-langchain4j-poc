package io.github.rodrigorjsf.agenticchat.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "A-1_b", "0123456789"})
    void acceptsSafeIds(String raw) {
        assertThat(new ConversationId(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",              // empty
            "a b",           // whitespace
            "a\nb",          // newline: RESP protocol boundary
            "conv:*",        // Valkey glob, would match other conversations under KEYS/SCAN
            "../other",      // path-ish traversal
            "sess#1",        // our own key separator
            "ãé",            // non-ascii
    })
    void rejectsUnsafeIds(String raw) {
        assertThatThrownBy(() -> new ConversationId(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIdsLongerThan64Chars() {
        assertThatThrownBy(() -> new ConversationId("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newIdIsSafeAndUnique() {
        var a = ConversationId.newId();
        var b = ConversationId.newId();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.value()).matches("[a-f0-9]{32}");
    }

    @Test
    void ofPassesThroughAnExistingId() {
        var id = ConversationId.newId();
        assertThat(ConversationId.of(id)).isSameAs(id);
        assertThat(ConversationId.of("plain")).isEqualTo(new ConversationId("plain"));
    }
}
