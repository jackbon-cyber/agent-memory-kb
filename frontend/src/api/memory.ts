import { request } from './client'
import type {
  EpisodicMemoryResponse,
  LongTermMemoryResponse,
  ShortTermMemoryResponse,
  WorkingMemoryResponse,
} from './types'

/**
 * 四层记忆的查看接口。
 *
 * <p>所有请求都带作用域请求头（由 `client.ts` 统一注入），
 * 后端据此把租户与主体隔离的记忆分出来。
 */

/** 查看短期记忆（会话内的消息序列）。 */
export function getShortTerm(sessionId?: string): Promise<ShortTermMemoryResponse> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  return request<ShortTermMemoryResponse>(`/api/memory/short-term${query}`)
}

/** 查看长期记忆。 */
export function getLongTerm(): Promise<LongTermMemoryResponse> {
  return request<LongTermMemoryResponse>('/api/memory/long-term')
}

/** 查看工作记忆。 */
export function getWorking(): Promise<WorkingMemoryResponse> {
  return request<WorkingMemoryResponse>('/api/memory/working')
}

/** 查看情景记忆。 */
export function getEpisodic(): Promise<EpisodicMemoryResponse> {
  return request<EpisodicMemoryResponse>('/api/memory/episodic')
}
