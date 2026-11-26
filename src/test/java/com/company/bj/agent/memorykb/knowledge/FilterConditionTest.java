package com.company.bj.agent.memorykb.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link FilterCondition} 的元数据过滤表达式构造规则。
 *
 * <p>核心是<b>取值类型敏感</b>：Chroma 的元数据等值匹配按值的实际类型比较，
 * 字符串列必须以字符串比较、数值列必须以数值比较。因此 {@code eq} 保持字符串原样，
 * 而范围类操作符必须把取值解析为 {@link Double}。
 */
class FilterConditionTest {

    @Test
    void 等值操作符保持字符串原样() {
        FilterCondition condition = new FilterCondition("file_name", "eq", "手册.txt");

        assertThat(condition.toWhere()).containsEntry("file_name", Map.of("$eq", "手册.txt"));
    }

    @Test
    void 等值操作符不把字符串转成数值() {
        // 数值型文本（如页码 "5"）若被转成 Double，与入库时写入的整型元数据比较会匹配不上。
        FilterCondition condition = new FilterCondition("page", "eq", "5");

        assertThat(condition.toWhere()).containsEntry("page", Map.of("$eq", "5"));
    }

    /**
     * 数值列的等值过滤必须传数字。
     *
     * <p>摄取管线写入的 {@code sequence} / {@code start_offset} / {@code end_offset}
     * 都是整数类型，而 Chroma 的等值匹配<b>类型敏感</b>：
     * 实测（live Chroma）用字符串 {@code "3"} 比较 int 型 {@code sequence} 会<b>零命中</b>，
     * 且没有任何错误提示。因此 {@code eq} 必须原样保留数字类型，
     * 由调用方负责传入与入库类型一致的取值。
     */
    @Test
    void 等值操作符保留数值列的数值类型() {
        FilterCondition condition = new FilterCondition("sequence", "eq", 3);

        assertThat(condition.toWhere()).containsEntry("sequence", Map.of("$eq", 3));
    }

    /**
     * {@code eq} 不会把整型偷偷变成浮点型。
     *
     * <p>Integer 与 Double 在 Chroma 的等值比较中都能命中 int 列，
     * 但服务端存储的类型与请求类型不同时行为可能变化，
     * 因此这里固定"原样保留"这一契约。
     */
    @Test
    void 等值操作符不把整型转成浮点型() {
        FilterCondition condition = new FilterCondition("sequence", "eq", 3);

        Object value = condition.toWhere().get("sequence") instanceof Map<?, ?> map
            ? map.get("$eq")
            : null;
        assertThat(value).isInstanceOf(Integer.class);
    }

    @Test
    void 范围过滤构造gte条件() {
        FilterCondition condition = new FilterCondition("page", "gte", "5");

        assertThat(condition.toWhere()).containsEntry("page", Map.of("$gte", 5.0));
    }

    @Test
    void 范围过滤支持全部四种比较操作符() {
        assertThat(new FilterCondition("page", "gt", "5").toWhere())
            .containsEntry("page", Map.of("$gt", 5.0));
        assertThat(new FilterCondition("page", "lt", "5").toWhere())
            .containsEntry("page", Map.of("$lt", 5.0));
        assertThat(new FilterCondition("page", "lte", "5").toWhere())
            .containsEntry("page", Map.of("$lte", 5.0));
    }

    @Test
    void 范围操作符遇到非数值抛异常() {
        assertThatThrownBy(() -> new FilterCondition("page", "gte", "abc").toWhere())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("page")
            .hasMessageContaining("gte");
    }

    @Test
    void 未知操作符抛异常() {
        assertThatThrownBy(() -> new FilterCondition("page", "ne", "5").toWhere())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ne");
    }

    @Test
    void 字段名为空时抛异常() {
        assertThatThrownBy(() -> new FilterCondition("", "eq", "5").toWhere())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 条件原样保留字段操作符与取值() {
        FilterCondition condition = new FilterCondition("kb", "eq", "k1");

        assertThat(condition.field()).isEqualTo("kb");
        assertThat(condition.operator()).isEqualTo("eq");
        assertThat(condition.value()).isEqualTo("k1");
    }
}
