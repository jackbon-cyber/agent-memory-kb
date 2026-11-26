import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import KnowledgeList from '../components/KnowledgeList.vue'

/**
 * 文档清单的测试。
 *
 * <p>覆盖空态、正常列出与删除后刷新三条路径。
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

describe('KnowledgeList', () => {
  it('知识库为空时展示空态', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(jsonResponse([]))

    const wrapper = mount(KnowledgeList)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('知识库还是空的')
    })
  })

  it('列出文档的文件名与切片数', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse([{ documentId: '手册.txt', fileName: '手册.txt', chunkCount: 12 }]),
    )

    const wrapper = mount(KnowledgeList)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('手册.txt')
    })
    expect(wrapper.text()).toContain('12')
  })

  it('删除文档后重新拉取清单', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementationOnce(
        jsonResponse([{ documentId: '手册.txt', fileName: '手册.txt', chunkCount: 12 }]),
      )
      .mockImplementationOnce(
        jsonResponse({ documentId: '手册.txt', deleted: true, chunkCount: 12 }),
      )
      .mockImplementationOnce(jsonResponse([]))

    const wrapper = mount(KnowledgeList)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('手册.txt')
    })

    await wrapper.get('table tbody button').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('知识库还是空的')
    })
    // 列表 + 删除 + 重新拉取。
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
