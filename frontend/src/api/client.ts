import type { ApiError } from './types'

/**
 * 请求层。
 *
 * <p>两件事在这里收口，避免散落在组件里：
 * ① <b>错误码 → 人话</b>：后端返回的是契约错误码，界面要显示可读说明，
 *    不能把错误码原样扔给用户；
 * ② <b>作用域请求头</b>：四层记忆按租户与主体隔离，
 *    后端从 `X-Tenant-Id` / `X-Owner-Key` 解析作用域，
 *    缺省回落到 `default`/`default`。
 */

/** 缺省租户标识。 */
export const DEFAULT_TENANT_ID = 'default'

/** 缺省主体标识。 */
export const DEFAULT_OWNER_KEY = 'default'

/** 租户标识请求头名。 */
export const HEADER_TENANT_ID = 'X-Tenant-Id'

/** 主体标识请求头名。 */
export const HEADER_OWNER_KEY = 'X-Owner-Key'

/**
 * 请求失败时抛出的错误。
 *
 * <p>带上 HTTP 状态与后端的契约错误码 —— 界面据此区分
 * 「参数不合法」「向量库不可用」「模型不可用」，而不是笼统显示「操作失败」。
 */
export class RequestFailure extends Error {
  readonly status: number
  readonly errorCode: string

  constructor(status: number, errorCode: string, message: string) {
    super(message)
    this.name = 'RequestFailure'
    this.status = status
    this.errorCode = errorCode
  }
}

/** 错误码 → 界面可读的说明。 */
const ERROR_TEXT: Record<string, string> = {
  INVALID_REQUEST: '请求参数不合法。',
  CHROMA_UNAVAILABLE: '向量库不可用，请确认 Chroma 服务已启动。',
  EMBEDDING_FAILED: '向量化失败，请检查模型配置与配额。',
  MEMORY_PERSISTENCE_FAILED: '记忆写入失败。',
  CONFLICT: '当前状态不允许该操作。',
  INTERNAL_ERROR: '服务端异常，请稍后重试。',
}

/** 把后端错误翻译成界面文案。 */
export function explain(error: unknown): string {
  if (error instanceof RequestFailure) {
    return ERROR_TEXT[error.errorCode] ?? error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return '未知错误'
}

/** 当前作用域。 */
let scope = { tenantId: DEFAULT_TENANT_ID, ownerKey: DEFAULT_OWNER_KEY }

/** 切换作用域。 */
export function setScope(next: { tenantId: string; ownerKey: string }): void {
  scope = {
    tenantId: next.tenantId || DEFAULT_TENANT_ID,
    ownerKey: next.ownerKey || DEFAULT_OWNER_KEY,
  }
}

/** 读取当前作用域。 */
export function getScope(): { tenantId: string; ownerKey: string } {
  return scope
}

/** 拼出带作用域请求头的头集合。 */
function scopeHeaders(): Headers {
  const headers = new Headers()
  headers.set(HEADER_TENANT_ID, scope.tenantId)
  headers.set(HEADER_OWNER_KEY, scope.ownerKey)
  return headers
}

/**
 * 发起一次 JSON 请求。
 *
 * <p>失败时抛出 {@link RequestFailure}，调用方只需 `explain(e)` 就能拿到人话。
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = scopeHeaders()
  headers.set('Accept', 'application/json')
  if (init.body) {
    headers.set('Content-Type', 'application/json')
  }
  // 合并调用方传入的头 —— HeadersInit 有多种形态（Headers / 数组 / 普通对象），
  // 统一先转成 Headers 再逐条覆盖。
  new Headers(init.headers).forEach((value, key) => headers.set(key, value))

  const response = await fetch(path, { ...init, headers })
  const text = await response.text()
  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      // 非 JSON 响应（例如网关返回的 HTML 错误页）按原始文本处理。
      payload = null
    }
  }

  if (!response.ok) {
    const error = payload as ApiError | null
    throw new RequestFailure(
      response.status,
      error?.error ?? 'UNKNOWN',
      error?.message ?? `请求失败（HTTP ${response.status}）`,
    )
  }
  return payload as T
}

/**
 * 上传文件。
 *
 * <p>走 `FormData` 而不是 JSON —— 文件内容必须以 multipart 提交，
 * 且**不能**手工设置 `Content-Type`（浏览器需要自己补上 multipart 边界）。
 */
export async function upload<T>(path: string, form: FormData): Promise<T> {
  const headers = scopeHeaders()
  headers.set('Accept', 'application/json')
  const response = await fetch(path, { method: 'POST', headers, body: form })
  const text = await response.text()
  const payload: unknown = text ? JSON.parse(text) : null

  if (!response.ok) {
    const error = payload as ApiError | null
    throw new RequestFailure(
      response.status,
      error?.error ?? 'UNKNOWN',
      error?.message ?? `上传失败（HTTP ${response.status}）`,
    )
  }
  return payload as T
}
