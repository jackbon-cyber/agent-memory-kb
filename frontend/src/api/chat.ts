import { request } from './client'
import type { ChatRequest, ChatResponse } from './types'

/**
 * 智能体问答接口。
 *
 * <p>回答与引用切片一并返回 —— 引用由后端在检索命中时收集，
 * 不从模型回答文本反推（模型会改写、漏写甚至编造来源）。
 */

/**
 * 向智能体提问。
 *
 * @param question  提问内容
 * @param sessionId 会话标识，缺省为后端的默认会话
 */
export function ask(question: string, sessionId?: string): Promise<ChatResponse> {
  const payload: ChatRequest = sessionId ? { question, sessionId } : { question }
  return request<ChatResponse>('/api/chat', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
