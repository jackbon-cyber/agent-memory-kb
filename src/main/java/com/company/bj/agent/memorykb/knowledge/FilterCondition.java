package com.company.bj.agent.memorykb.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 元数据过滤条件。
 *
 * <p>描述"在某个元数据字段上施加一个比较操作"，可由 {@link FilterCondition#toWhere()}
 * 翻译成 Chroma 的 {@code where} 表达式片段。
 *
 * <p><b>取值类型敏感</b>：Chroma 的元数据比较按值的<b>实际类型</b>进行 ——
 * 字符串列必须以字符串比较，数值列必须以数值比较，类型不匹配会静默不命中。
 * 因此本记录按操作符分两类处理取值：
 * <ul>
 *   <li>{@code eq} —— 取值<b>原样保留</b>（字符串就是字符串）。若在此处擅自把
 *       {@code "5"} 变成 {@code 5.0}，与入库时写入的整型元数据比较将永远匹配不上；</li>
 *   <li>{@code gt} / {@code gte} / {@code lt} / {@code lte} —— 取值解析为
 *       {@link Double}，解析失败抛 {@link IllegalArgumentException}。</li>
 * </ul>
 *
 * <p><b>⚠️ 调用方须知：{@code eq} 的取值类型必须与入库时的元数据类型一致</b>。
 * Chroma 的等值匹配是<b>类型敏感</b>的：实测（live Chroma）把入库的 int 型
 * {@code sequence} 用字符串 {@code "3"} 比较会<b>零命中</b>，且<b>没有任何错误提示</b>；
 * 传 Integer {@code 3} 或 Double {@code 3.0} 则正常命中。
 * 本工程的摄取管线写入的 {@code sequence} / {@code start_offset} / {@code end_offset}
 * 都是<b>整数</b>类型，因此对这些列做等值过滤时<b>必须传 {@link Number}
 * 而非字符串</b>：
 * <pre>{@code
 * // 正确：数值列传数字
 * new FilterCondition("sequence", "eq", 3)
 * // 错误：数值列传字符串 —— 静默返回空结果
 * new FilterCondition("sequence", "eq", "3")
 * }</pre>
 * 字符串列（如 {@code file_name}）则必须传字符串。
 *
 * @param field    元数据字段名
 * @param operator 比较操作符，取值为 {@code eq} / {@code gt} / {@code gte} / {@code lt} / {@code lte}
 * @param value    比较取值；{@code eq} 时原样保留，须与入库时的元数据类型一致
 */
public record FilterCondition(String field, String operator, Object value) {

    /**
     * 规范化构造：<b>在对象创建时就完成校验与取值规范化</b>。
     *
     * <p>之所以把校验前移到构造期，是因为过滤条件最终来自 HTTP 请求，
     * 非法条件属于请求错误，必须在接口边界就暴露出 400；
     * 若推迟到 {@link #toWhere()} 被调用时才校验，一旦检索器被替身替换
     * 或换成别的实现，非法条件就会<b>静默通过</b>并返回全量结果。
     *
     * <p>规范化规则：
     * <ul>
     *   <li>{@code eq} —— 取值<b>原样保留</b>（字符串就是字符串），避免制造静默零命中；</li>
     *   <li>范围类操作符 —— 取值解析为 {@link Double}，解析失败直接拒绝。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 字段名为空、操作符不支持，
     *                                  或范围类操作符的取值无法解析为数值时抛出
     */
    public FilterCondition {
        field = validateField(field);
        operator = validateOperator(operator, field);
        value = normalizeValue(value, operator, field);
    }

    /** 等值操作符。 */
    public static final String OPERATOR_EQ = "eq";

    /** 大于操作符。 */
    public static final String OPERATOR_GT = "gt";

    /** 大于等于操作符。 */
    public static final String OPERATOR_GTE = "gte";

    /** 小于操作符。 */
    public static final String OPERATOR_LT = "lt";

    /** 小于等于操作符。 */
    public static final String OPERATOR_LTE = "lte";

    /** 等值操作符在 Chroma where 表达式中的键名。 */
    private static final String WHERE_EQ = "$eq";

    /** 大于操作符在 Chroma where 表达式中的键名。 */
    private static final String WHERE_GT = "$gt";

    /** 大于等于操作符在 Chroma where 表达式中的键名。 */
    private static final String WHERE_GTE = "$gte";

    /** 小于操作符在 Chroma where 表达式中的键名。 */
    private static final String WHERE_LT = "$lt";

    /** 小于等于操作符在 Chroma where 表达式中的键名。 */
    private static final String WHERE_LTE = "$lte";

    /**
     * 把本条件翻译成 Chroma 的 {@code where} 表达式片段。
     *
     * <p>片段形如 <code>{字段: {"$eq": 取值}}</code>。
     *
     * @return where 表达式片段
     * @throws IllegalArgumentException 字段名为空、操作符不支持，
     *                                  或范围类操作符的取值无法解析为数值时抛出
     */
    public Map<String, Object> toWhere() {
        Map<String, Object> comparison = new LinkedHashMap<>(1);
        comparison.put(whereOperatorKey(), value);
        Map<String, Object> where = new LinkedHashMap<>(1);
        where.put(field, comparison);
        return where;
    }

    /**
     * 校验字段名非空。
     *
     * @param field 字段名
     * @return 校验通过的字段名
     * @throws IllegalArgumentException 字段名为空或空白时抛出
     */
    private static String validateField(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("filter field must not be blank");
        }
        return field;
    }

    /**
     * 校验操作符受支持。
     *
     * @param operator 比较操作符
     * @param field    字段名，用于错误提示
     * @return 原样返回的操作符
     * @throws IllegalArgumentException 操作符不受支持时抛出
     */
    private static String validateOperator(String operator, String field) {
        switch (operator == null ? "" : operator) {
            case OPERATOR_EQ, OPERATOR_GT, OPERATOR_GTE, OPERATOR_LT, OPERATOR_LTE -> {
                return operator;
            }
            default -> throw new IllegalArgumentException(
                "unsupported filter operator: '" + operator + "' on field '" + field
                    + "', expected one of eq/gt/gte/lt/lte");
        }
    }

    /**
     * 把受支持的操作符翻译为 Chroma where 表达式中的键名。
     *
     * @return where 操作符键名
     */
    private String whereOperatorKey() {
        return switch (operator) {
            case OPERATOR_EQ -> WHERE_EQ;
            case OPERATOR_GT -> WHERE_GT;
            case OPERATOR_GTE -> WHERE_GTE;
            case OPERATOR_LT -> WHERE_LT;
            case OPERATOR_LTE -> WHERE_LTE;
            default -> throw new IllegalStateException("unreachable: operator already validated");
        };
    }

    /**
     * 按操作符规范化取值。
     *
     * <p>等值操作符保持取值原样，范围类操作符把取值解析为 {@link Double}。
     *
     * @param value    原始取值
     * @param operator 比较操作符
     * @param field    字段名，用于错误提示
     * @return 规范化后的取值
     * @throws IllegalArgumentException 范围类操作符的取值无法解析为数值时抛出
     */
    private static Object normalizeValue(Object value, String operator, String field) {
        if (OPERATOR_EQ.equals(operator)) {
            return value;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            throw new IllegalArgumentException(
                "filter value must not be null for operator '" + operator + "' on field '" + field + "'");
        }
        try {
            return Double.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("filter value '" + value + "' on field '" + field
                + "' with operator '" + operator + "' is not a number", e);
        }
    }
}
