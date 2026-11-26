/**
 * 后端 REST 契约的 TypeScript 镜像。
 *
 * <p>字段名与 `com.company.bj.agent.memorykb.web.dto` 下的 record **逐字一致** ——
 * 后端改字段名，这里必须同步改，否则 TS 编译期不报错、运行期拿到 undefined。
 */

/** 元数据过滤操作符。 */
export type FilterOperator = 'eq' | 'gt' | 'gte' | 'lt' | 'lte'

/** 多条件的组合逻辑。 */
export type FilterLogic = 'AND' | 'OR'

/** 单条元数据过滤条件。 */
export interface FilterCondition {
  field: string
  operator: FilterOperator
  value: string
}

/** 检索请求。 */
export interface SearchRequest {
  query: string
  filters: FilterCondition[]
  logic: FilterLogic
  topK: number
  scoreThreshold?: number
}

/** 检索命中的切片。 */
export interface RetrievedChunk {
  chunkId: string
  content: string
  score: number
  metadata: Record<string, unknown>
}

/** 检索响应。 */
export interface SearchResponse {
  hits: RetrievedChunk[]
  tookMs: number
  total: number
}

/** 已入库文档摘要。 */
export interface DocumentSummary {
  documentId: string
  fileName: string
  chunkCount: number
}

/** 入库结果。 */
export interface IngestionResponse {
  documentId: string
  chunkCount: number
  ingestedCount: number
}

/** 删除文档结果。 */
export interface DeleteDocumentResponse {
  documentId: string
  deleted: boolean
  chunkCount: number
}

/** 记忆作用域。 */
export interface MemoryScope {
  tenantId: string
  ownerKey: string
}

/** 长期记忆记录。 */
export interface MemoryRecord {
  id: string
  content: string
  weight: number
  createdAt: number
  scope: MemoryScope
  archived: boolean
}

/** 工作记忆任务项。 */
export interface TaskItem {
  id: string
  description: string
  status: string
  createdAt: number
}

/** 情景记忆事件。 */
export interface Episode {
  id: string
  type: string
  events: string[]
  occurredAt: number
  scope: MemoryScope
}

/** 短期记忆视图。 */
export interface ShortTermMemoryResponse {
  messages: string[]
}

/** 长期记忆视图。 */
export interface LongTermMemoryResponse {
  records: MemoryRecord[]
}

/** 工作记忆视图。 */
export interface WorkingMemoryResponse {
  tasks: TaskItem[]
}

/** 情景记忆视图。 */
export interface EpisodicMemoryResponse {
  episodes: Episode[]
}

/** 问答请求。 */
export interface ChatRequest {
  question: string
  sessionId?: string
}

/** 问答响应。 */
export interface ChatResponse {
  answer: string
  citations: RetrievedChunk[]
}

/** 健康检查响应。 */
export interface HealthResponse {
  status: string
  components: Record<string, string>
}

/** 后端统一错误响应。 */
export interface ApiError {
  error: string
  message: string
  path: string
}
