package com.company.bj.agent.memorykb.web.dto;

import java.util.List;

/**
 * 检索响应。
 *
 * <p>{@code tookMs} 是<b>服务端实测耗时</b>（含嵌入与向量检索），
 * 不是客户端往返时间 —— 控制台展示的"检索耗时"要能反映后端真实负载。
 *
 * @param hits   命中列表，按相似度降序
 * @param tookMs 服务端实测耗时（毫秒）
 * @param total  命中总数
 */
public record SearchResponse(List<HitResponse> hits, long tookMs, int total) {

    /**
     * 规范化构造，保证命中列表永不为 {@code null}。
     *
     * @param hits   命中列表
     * @param tookMs 服务端实测耗时（毫秒）
     * @param total  命中总数
     */
    public SearchResponse {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
