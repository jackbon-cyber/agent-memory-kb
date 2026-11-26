import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentUpload from '../components/DocumentUpload.vue'
import { explain, RequestFailure } from '../api/client'

/**
 * 文档上传组件的测试。
 *
 * <p>覆盖三条路径：上传成功（展示切片数）、上传失败（展示可读错误）、
 * 未选文件时按钮禁用。
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

/** 挂载组件。 */
async function mountUpload() {
  const wrapper = mount(DocumentUpload)
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 触发文件选择。 */
async function selectFile(wrapper: Awaited<ReturnType<typeof mountUpload>>, name: string): Promise<void> {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', {
    value: [new File(['内容'], name, { type: 'text/plain' })],
    writable: true,
  })
  await input.trigger('change')
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('DocumentUpload', () => {
  it('上传成功后展示切片数量', async () => {
    const uploadMock = vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({ documentId: '手册.txt', chunkCount: 12, ingestedCount: 12 }),
    )

    const wrapper = await mountUpload()
    await selectFile(wrapper, '手册.txt')

    // 提交前按钮可用。
    const button = wrapper.get('button')
    expect(button.attributes('disabled')).toBeUndefined()

    await button.trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('12')
    })

    expect(uploadMock).toHaveBeenCalled()
  })

  it('未选文件时提交按钮禁用', async () => {
    const wrapper = await mountUpload()
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  })

  it('上传失败时展示可读错误', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse(
        { error: 'INVALID_REQUEST', message: 'unsupported file type', path: '/api/documents' },
        400,
      ),
    )

    const wrapper = await mountUpload()
    await selectFile(wrapper, '手册.pdf')
    await wrapper.get('button').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('参数不合法')
    })
  })

  it('上传成功后通知父组件刷新文档清单', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      jsonResponse({ documentId: '手册.txt', chunkCount: 3, ingestedCount: 3 }),
    )

    const wrapper = await mountUpload()
    await selectFile(wrapper, '手册.txt')
    await wrapper.get('button').trigger('click')

    await vi.waitFor(() => {
      expect(wrapper.emitted('uploaded')).toBeTruthy()
    })
  })

  it('错误码映射为可读文案', () => {
    expect(explain(new RequestFailure(400, 'INVALID_REQUEST', 'raw'))).toBe('请求参数不合法。')
    expect(explain(new Error('plain'))).toBe('plain')
  })
})
