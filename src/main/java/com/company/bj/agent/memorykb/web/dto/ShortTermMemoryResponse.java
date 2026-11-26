package com.company.bj.agent.memorykb.web.dto;

import java.util.List;

/**
 * 短期记忆视图。
 *
 * @param messages 会话内的消息序列，顺序即语义
 */
public record ShortTermMemoryResponse(List<String> messages) {

    /**
     * 规范化构造，保证消息列表永不为 {@code null}。
     *
     * @param messages 会话内的消息序列
     */
    public ShortTermMemoryResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
