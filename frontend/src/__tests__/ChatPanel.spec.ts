import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatPanel from '../components/ChatPanel.vue'

/**
 * 问答面板的测试。
 *
 * <p>重点钉住：回答与引用切片<b>一并展示</b> ——
 * 引用由后端在检索命中时收集，界面必须把两者都呈现出来，
 * 否则用户无法判断回答的依据。
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
function jsonResponse(payload: unknown, status = 200): () => Response {
  return () =>
    new Response(JSON.stringify(payload), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
}

describe('ChatPanel', () => {
  it('提问成功后展示回答与引用片段', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({
        answer: '边检周期为每周一次。',
        citations: [
          {
            chunkId: '手册.txt#0',
            content: '边检周期为每周一次',
            score: 0.91,
            metadata: { file_name: '手册.txt' },
          },
        ],
      }),
    )

    const wrapper = mount(ChatPanel)
    await wrapper.get('input[aria-label="提问"]').setValue('边检周期是多久？')
    await wrapper.get('button.btn-primary').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('边检周期为每周一次。')
    })
    expect(wrapper.text()).toContain('引用片段')
    expect(wrapper.text()).toContain('手册.txt')
  })

  it('提问失败时展示可读错误', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({ error: 'EMBEDDING_FAILED', message: 'boom', path: '/api/chat' }, 502),
    )

    const wrapper = mount(ChatPanel)
    await wrapper.get('input[aria-label="提问"]').setValue('q')
    await wrapper.get('button.btn-primary').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('向量化失败')
    })
  })

  it('问题为空时不发请求', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')

    const wrapper = mount(ChatPanel)
    await wrapper.get('button.btn-primary').trigger('click')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入问题')
  })

  it('没有引用时不渲染引用区块', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({ answer: '不知道', citations: [] }),
    )

    const wrapper = mount(ChatPanel)
    await wrapper.get('input[aria-label="提问"]').setValue('q')
    await wrapper.get('button.btn-primary').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('不知道')
    })
    expect(wrapper.text()).not.toContain('引用片段')
  })
})
