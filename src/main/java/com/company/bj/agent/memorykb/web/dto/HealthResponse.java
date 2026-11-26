package com.company.bj.agent.memorykb.web.dto;

import java.util.Map;

/**
 * 健康检查响应。
 *
 * <p>整体 {@code status} 为 {@code DOWN} 时，{@code components} 中
 * 至少有一项不是 {@code UP} —— 调用方不必逐项比较即可判定。
 *
 * @param status     整体状态，取值为 {@code UP} / {@code DOWN}
 * @param components 各组件状态，键为组件名
 */
public record HealthResponse(String status, Map<String, String> components) {

    /** 正常状态。 */
    public static final String STATUS_UP = "UP";

    /** 异常状态。 */
    public static final String STATUS_DOWN = "DOWN";

    /**
     * 规范化构造，保证组件映射永不为 {@code null}。
     *
     * @param status     整体状态
     * @param components 各组件状态
     */
    public HealthResponse {
        components = components == null ? Map.of() : Map.copyOf(components);
    }
}
