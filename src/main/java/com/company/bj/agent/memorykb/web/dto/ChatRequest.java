package com.company.bj.agent.memorykb.web.dto;

/**
 * 问答请求。
 *
 * @param question  用户问题
 * @param sessionId 会话标识，缺省为 {@code default}；
 *                  同一会话的多轮对话共享短期记忆上下文
 */
public record ChatRequest(String question, String sessionId) {
}
