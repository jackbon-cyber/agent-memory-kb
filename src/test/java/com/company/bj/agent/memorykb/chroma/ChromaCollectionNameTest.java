package com.company.bj.agent.memorykb.chroma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 校验集合名的合法性规则在发起任何 HTTP 请求之前就被拒绝。
 *
 * <p>Chroma v2 服务端会对非法集合名返回 4xx，本地先做一次校验可以避免无谓的网络往返，
 * 也让调用方拿到明确的 {@link IllegalArgumentException} 而非 {@link ChromaException}。
 */
class ChromaCollectionNameTest {

    @Test
    void 合法名称原样返回() {
        assertThat(ChromaCollectionName.validate("kb_main")).isEqualTo("kb_main");
        assertThat(ChromaCollectionName.validate("mem.long-term_1")).isEqualTo("mem.long-term_1");
    }

    @Test
    void 过短名称被拒() {
        assertThatThrownBy(() -> ChromaCollectionName.validate("kb"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("collection name");
    }

    @Test
    void 非法字符被拒() {
        assertThatThrownBy(() -> ChromaCollectionName.validate("kb main"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("collection name");
        assertThatThrownBy(() -> ChromaCollectionName.validate("_kb_main"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("collection name");
    }

    @Test
    void 首尾非字母数字被拒() {
        assertThatThrownBy(() -> ChromaCollectionName.validate("kb_"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("collection name");
    }

    @Test
    void 空名与超长名被拒() {
        assertThatThrownBy(() -> ChromaCollectionName.validate(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChromaCollectionName.validate(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChromaCollectionName.validate("a".repeat(513)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 边界长度名被接受() {
        assertThat(ChromaCollectionName.validate("abc")).isEqualTo("abc");
        assertThat(ChromaCollectionName.validate("a".repeat(512))).isEqualTo("a".repeat(512));
    }
}
