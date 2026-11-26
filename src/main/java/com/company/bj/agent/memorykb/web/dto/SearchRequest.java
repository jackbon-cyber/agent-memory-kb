package com.company.bj.agent.memorykb.web.dto;

import com.company.bj.agent.memorykb.knowledge.FilterCondition;
import com.company.bj.agent.memorykb.knowledge.FilterLogic;
import java.util.List;

/**
 * 检索请求。
 *
 * <p><b>过滤取值的类型转换规则</b>（与前端 Task 10 的约定逐字一致）：
 * <ul>
 *   <li>{@code eq} —— 取值<b>原样保留为字符串</b>。
 *       Chroma 的等值匹配是<b>类型敏感</b>的：入库时写入的
 *       {@code sequence} 是整型，用字符串 {@code "3"} 比较会<b>静默零命中</b>，
 *       而 {@code file_name} 这类字符串列必须传字符串。
 *       因此 {@code eq} 一律按字符串处理，由用户在字段名上区分用途；</li>
 *   <li>{@code gt} / {@code gte} / {@code lt} / {@code lte} —— 取值解析为
 *       {@link Double}，解析失败抛 {@link IllegalArgumentException} → HTTP 400。</li>
 * </ul>
 *
 * @param query          查询文本
 * @param filters        元数据过滤条件，无过滤时为空列表
 * @param logic          多条件的组合逻辑，缺省为 {@link FilterLogic#AND}
 * @param topK           返回条数上限，缺省为 10
 * @param scoreThreshold 相似度下限，缺省为不施加下限
 */
public record SearchRequest(
    String query,
    List<FilterRequest> filters,
    FilterLogic logic,
    Integer topK,
    Double scoreThreshold) {

    /** 默认返回条数。 */
    public static final int DEFAULT_TOP_K = 10;

    /** 返回条数上限，防止一次拉取过多片段拖垮控制台。 */
    public static final int MAX_TOP_K = 50;

    /**
     * 规范化构造，处理 {@code null} 列表与缺省值。
     *
     * @param query          查询文本
     * @param filters        元数据过滤条件
     * @param logic          多条件的组合逻辑
     * @param topK           返回条数上限
     * @param scoreThreshold 相似度下限
     */
    public SearchRequest {
        filters = filters == null ? List.of() : List.copyOf(filters);
        logic = logic == null ? FilterLogic.AND : logic;
        topK = topK == null ? DEFAULT_TOP_K : Math.min(Math.max(topK, 1), MAX_TOP_K);
    }

    /**
     * 转换为领域层的过滤条件。
     *
     * @return 领域层过滤条件列表
     * @throws IllegalArgumentException 字段名为空、操作符不支持或范围操作符的取值无法解析为数值时抛出
     */
    public List<FilterCondition> toFilterConditions() {
        List<FilterCondition> conditions = new java.util.ArrayList<>(filters.size());
        for (FilterRequest filter : filters) {
            conditions.add(filter.toCondition());
        }
        return conditions;
    }
}
