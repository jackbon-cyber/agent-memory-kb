package com.company.bj.agent.memorykb.web.dto;

import com.company.bj.agent.memorykb.memory.Episode;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import java.util.List;

/**
 * 情景记忆条目的对外形状。
 *
 * @param id         情景标识
 * @param type       情景类型
 * @param events     事件序列，顺序敏感
 * @param occurredAt 发生时间戳（epoch 毫秒）
 * @param scope      归属作用域
 */
public record EpisodeResponse(
    String id,
    String type,
    List<String> events,
    long occurredAt,
    MemoryScope scope) {

    /**
     * 规范化构造，冻结事件列表。
     *
     * @param id         情景标识
     * @param type       情景类型
     * @param events     事件序列
     * @param occurredAt 发生时间戳
     * @param scope      归属作用域
     */
    public EpisodeResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }

    /**
     * 由领域情景转换为对外形状。
     *
     * @param episode 领域情景
     * @return 对外形状
     */
    public static EpisodeResponse from(Episode episode) {
        return new EpisodeResponse(episode.id(), episode.type(), episode.events(),
            episode.occurredAt(), episode.scope());
    }

    /**
     * 批量转换。
     *
     * @param episodes 领域情景列表
     * @return 对外形状列表
     */
    public static List<EpisodeResponse> from(List<Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            return List.of();
        }
        List<EpisodeResponse> responses = new java.util.ArrayList<>(episodes.size());
        for (Episode episode : episodes) {
            responses.add(from(episode));
        }
        return List.copyOf(responses);
    }
}
