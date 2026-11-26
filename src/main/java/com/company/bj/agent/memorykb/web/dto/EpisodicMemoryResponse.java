package com.company.bj.agent.memorykb.web.dto;

import java.util.List;

/**
 * 情景记忆视图。
 *
 * @param episodes 当前作用域下的情景条目
 */
public record EpisodicMemoryResponse(List<EpisodeResponse> episodes) {

    /**
     * 规范化构造，保证情景列表永不为 {@code null}。
     *
     * @param episodes 当前作用域下的情景条目
     */
    public EpisodicMemoryResponse {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }
}
