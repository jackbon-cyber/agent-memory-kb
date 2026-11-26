import { request, upload } from './client'
import type {
  DeleteDocumentResponse,
  DocumentSummary,
  IngestionResponse,
  SearchRequest,
  SearchResponse,
} from './types'

/**
 * 知识库相关接口。
 *
 * <p>请求与响应的字段名与后端 `web.dto` 下的 record 逐字一致。
 */

/**
 * 上传并入库一份文档。
 *
 * @param file 待上传的文件，仅支持 `.txt` / `.xlsx` / `.xls`
 * @param kb   可选的知识库标识，作为附加元数据写入每个切片
 */
export function uploadDocument(file: File, kb?: string): Promise<IngestionResponse> {
  const form = new FormData()
  form.append('file', file)
  if (kb) {
    form.append('kb', kb)
  }
  return upload<IngestionResponse>('/api/documents', form)
}

/** 列出知识库中已入库的文档。 */
export function listDocuments(): Promise<DocumentSummary[]> {
  return request<DocumentSummary[]>('/api/documents')
}

/**
 * 删除一个文档及其全部切片。
 *
 * @param documentId 文档标识（即原始文件名）
 */
export function deleteDocument(documentId: string): Promise<DeleteDocumentResponse> {
  return request<DeleteDocumentResponse>(`/api/documents/${encodeURIComponent(documentId)}`, {
    method: 'DELETE',
  })
}

/**
 * 执行一次带元数据过滤的语义检索。
 *
 * <p><b>过滤取值一律按字符串提交</b>：类型由操作符决定 ——
 * `eq` 保持字符串（Chroma 的等值匹配类型敏感，字符串列必须传字符串），
 * 范围类操作符由后端解析为数值。前端不做自动类型嗅探。
 */
export function search(payload: SearchRequest): Promise<SearchResponse> {
  return request<SearchResponse>('/api/retrieval/search', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
