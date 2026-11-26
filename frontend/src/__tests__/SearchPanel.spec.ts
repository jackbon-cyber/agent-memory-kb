import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SearchPanel from '../components/SearchPanel.vue'
import type { FilterCondition } from '../api/types'

/**
 * 检索面板的测试。
 *
 * <p>重点钉住两件事：
 * <ul>
 *   <li><b>过滤条件原样提交</b> —— 取值不因前端而被改写；</li>
 *   <li><b>检索失败要把错误抛给父组件</b> —— 否则界面会挂着一份「看起来正常」的
 *       空结果，让人以为「确实没有命中」。</li>
 * </ul>
 */

/**
 * 构造一个 JSON 响应的工厂。
 *
 * <p><b>必须是工厂而不是单个实例</b>：{@code Response} 的 body 只能读一次，
 * 同一个实例被复用时第二次调用会抛
 * 「Body is unusable: Body has already been read」。
 */
function jsonResponse(payload: unknown, status = 200): () => Response {
  return () =>
    new Response(JSON.stringify(payload), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('SearchPanel', () => {
  it('把过滤条件原样提交给后端', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({ hits: [], tookMs: 7, total: 0 }),
    )

    const filters: FilterCondition[] = [
      { field: 'file_name', operator: 'eq', value: '手册.txt' },
      { field: 'sequence', operator: 'gte', value: '3' },
    ]

    const wrapper = mount(SearchPanel, { props: { filters, logic: 'AND' } })
    await wrapper.get('input[aria-label="检索查询"]').setValue('边检周期')
    await wrapper.get('button.btn-primary').trigger('click')

    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalled()
    })

    const body = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body)) as {
      filters: FilterCondition[]
      logic: string
      query: string
    }
    expect(body.query).toBe('边检周期')
    expect(body.logic).toBe('AND')
    // 取值必须是字符串 —— 后端按操作符决定类型。
    expect(body.filters[1]?.value).toBe('3')
    expect(typeof body.filters[1]?.value).toBe('string')
  })

  it('检索成功后把命中与耗时抛给父组件', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({
        hits: [{ chunkId: 'a#0', content: '内容', score: 0.9, metadata: {} }],
        tookMs: 12,
        total: 1,
      }),
    )

    const wrapper = mount(SearchPanel, { props: { filters: [], logic: 'AND' } })
    await wrapper.get('input[aria-label="检索查询"]').setValue('q')
    await wrapper.get('button.btn-primary').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.emitted('result')).toBeTruthy()
    })
    const payload = wrapper.emitted('result')?.[0] as [unknown[], number]
    expect(payload[0]).toHaveLength(1)
    expect(payload[1]).toBe(12)
  })

  it('检索失败时通知父组件并展示错误', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({ error: 'INVALID_REQUEST', message: 'bad', path: '/api/retrieval/search' }, 400),
    )

    const wrapper = mount(SearchPanel, { props: { filters: [], logic: 'AND' } })
    await wrapper.get('input[aria-label="检索查询"]').setValue('q')
    await wrapper.get('button.btn-primary').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('参数不合法')
    })
    expect(wrapper.emitted('failed')).toBeTruthy()
  })

  it('查询为空时不发请求', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')

    const wrapper = mount(SearchPanel, { props: { filters: [], logic: 'AND' } })
    await wrapper.get('button.btn-primary').trigger('click')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入查询内容')
  })
})
