package com.company.bj.agent.memorykb.agent;

import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import java.util.List;

/**
 * 智能体问答结果。
 *
 * <p>{@code citations} 是本次问答<b>实际命中</b>的知识库切片，
 * 由检索侧在命中时记录 —— 不是从模型回答文本里反推的。
 *
 * @param answer    模型给出的最终回答文本
 * @param citations 本次问答引用的知识库切片，按首次命中顺序排列
 */
public record ChatResult(String answer, List<RetrievedChunk> citations) {

    /**
     * 规范化字段，保证下游拿到的引用列表永不为 {@code null}。
     */
    public ChatResult {
        answer = answer == null ? "" : answer;
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
