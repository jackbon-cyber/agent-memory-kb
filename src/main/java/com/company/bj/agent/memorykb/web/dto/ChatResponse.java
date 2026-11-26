package com.company.bj.agent.memorykb.web.dto;

import java.util.List;

/**
 * 问答响应。
 *
 * <p>{@code citations} 是本次回答<b>实际引用</b>的知识库切片，
 * 由检索工具在命中时写入收集器，而非从模型回答文本反推 ——
 * 模型会改写、漏写甚至编造来源，反推出来的引用不可信。
 *
 * @param answer    模型回答文本
 * @param citations 本次回答引用的知识库切片
 */
public record ChatResponse(String answer, List<HitResponse> citations) {

    /**
     * 规范化构造，保证引用列表永不为 {@code null}。
     *
     * @param answer    模型回答文本
     * @param citations 本次回答引用的知识库切片
     */
    public ChatResponse {
        answer = answer == null ? "" : answer;
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
