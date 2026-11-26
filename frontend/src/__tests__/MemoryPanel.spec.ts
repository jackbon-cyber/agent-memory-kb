import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MemoryPanel from '../components/MemoryPanel.vue'

/**
 * 四层记忆面板的测试。
 *
 * <p>重点钉住两件事：
 * <ul>
 *   <li>切换页签会按需拉取对应数据 —— 不能永远只显示第一层；</li>
 *   <li>请求带上了作用域请求头 —— 后端据此隔离租户与主体的记忆。</li>
 * </ul>
 */

afterEach(() => {
  vi.restoreAllMocks()
})

/**
 * 构造一个 JSON 响应的工厂。
 *
 * <p><b>必须是工厂而不是单个实例</b>：{@code Response} 的 body 只能读一次，
 * 同一个实例被复用时第二次调用会抛
 * 「Body is unusable: Body has already been read」。
 */
function jsonResponse(payload: unknown): () => Response {
  return () =>
    new Response(JSON.stringify(payload), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
}

describe('MemoryPanel', () => {
  it('默认展示短期记忆并带上作用域请求头', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(jsonResponse({ messages: ['[user] 边检周期是什么'] }))

    const wrapper = mount(MemoryPanel)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('边检周期是什么')
    })

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers
    expect(headers.get('X-Tenant-Id')).toBe('default')
    expect(headers.get('X-Owner-Key')).toBe('default')
  })

  it('切换到长期记忆页签会拉取长期记忆', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({
        records: [
          {
            id: 'r1',
            content: '边检周期为每周一次',
            weight: 0.8,
            createdAt: 1700000000000,
            scope: { tenantId: 'default', ownerKey: 'default' },
            archived: false,
          },
        ],
      }),
    )

    const wrapper = mount(MemoryPanel)
    const tabs = wrapper.findAll('button[role="tab"]')
    await tabs[1]?.trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('边检周期为每周一次')
    })
    expect(String(fetchMock.mock.calls.at(-1)?.[0])).toContain('/api/memory/long-term')
  })

  it('切换到工作记忆页签会展示任务状态', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({
        tasks: [{ id: 't1', description: '复核边检记录', status: 'DONE', createdAt: 1700000000000 }],
      }),
    )

    const wrapper = mount(MemoryPanel)
    const tabs = wrapper.findAll('button[role="tab"]')
    await tabs[2]?.trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('复核边检记录')
    })
    expect(wrapper.text()).toContain('DONE')
  })

  it('切换到情景记忆页签会展示事件流', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({
        episodes: [
          {
            id: 'e1',
            type: 'RETRIEVAL',
            events: ['检索了边检周期'],
            occurredAt: 1700000000000,
            scope: { tenantId: 'default', ownerKey: 'default' },
          },
        ],
      }),
    )

    const wrapper = mount(MemoryPanel)
    const tabs = wrapper.findAll('button[role="tab"]')
    await tabs[3]?.trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('检索了边检周期')
    })
  })

  it('加载失败时展示可读错误', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      () =>
        new Response(
          JSON.stringify({ error: 'INTERNAL_ERROR', message: 'boom', path: '/api/memory/short-term' }),
          { status: 500, headers: { 'Content-Type': 'application/json' } },
        ),
    )

    const wrapper = mount(MemoryPanel)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('服务端异常')
    })
  })
})
