package com.company.bj.agent.memorykb.web.dto;

import com.company.bj.agent.memorykb.knowledge.FilterCondition;

/**
 * 单条元数据过滤条件。
 *
 * <p><b>取值一律按字符串接收</b>：前端输入框天然产出字符串，
 * 而 Chroma 的等值匹配是类型敏感的。此处不做「自动类型嗅探」——
 * 自动嗅探会在 {@code file_name = "123"}（文件名恰好是数字）这类场景下
 * 把字符串悄悄变成数值，反而制造零命中。类型由操作符决定：
 * {@code eq} 保持字符串，范围类操作符在领域层解析为数值。
 *
 * @param field    元数据字段名
 * @param operator 比较操作符，取值为 {@code eq} / {@code gt} / {@code gte} / {@code lt} / {@code lte}
 * @param value    比较取值
 */
public record FilterRequest(String field, String operator, String value) {

    /**
     * 转换为领域层的过滤条件。
     *
     * <p><b>转换时立即校验</b>，而不是等到检索器构造 {@code where} 表达式时才校验。
     * 理由是过滤条件来自 HTTP 请求，非法条件属于<b>请求错误</b>，
     * 必须在接口边界就被拒成 400；若把校验推迟到检索器内部，
     * 一旦检索器换实现（或被替身替换），非法请求就会<b>静默通过</b>并返回全量结果 ——
     * 用户会以为过滤生效了，实际拿到的是没过滤的数据。
     *
     * @return 领域层过滤条件
     * @throws IllegalArgumentException 字段名为空、操作符不支持，
     *                                  或范围类操作符的取值无法解析为数值时抛出
     */
    public FilterCondition toCondition() {
        // FilterCondition 的构造期即完成校验与取值规范化，
        // 因此非法条件在这里就会抛出 IllegalArgumentException → HTTP 400。
        return new FilterCondition(field, operator, value);
    }
}
