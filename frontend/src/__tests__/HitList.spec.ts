import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import HitList from '../components/HitList.vue'
import type { RetrievedChunk } from '../api/types'

/**
 * 命中列表的测试。
 *
 * <p>覆盖三种界面状态：未检索、检索但无命中、有命中。
 * 空状态必须说清楚是「没搜过」还是「搜了没结果」——
 * 这两种情况给用户的下一步动作完全不同。
 */

/** 构造一条命中。 */
function makeHit(overrides: Partial<RetrievedChunk> = {}): RetrievedChunk {
  return {
    chunkId: '手册.txt#0',
    content: '边检周期为每周一次',
    score: 0.9312,
    metadata: { file_name: '手册.txt', start_offset: 0, end_offset: 120 },
    ...overrides,
  }
}

describe('HitList', () => {
  it('未检索时提示尚未检索', () => {
    const wrapper = mount(HitList, { props: { hits: [], searched: false } })
    expect(wrapper.text()).toContain('尚未检索')
  })

  it('检索后无命中时提示没有命中', () => {
    const wrapper = mount(HitList, { props: { hits: [], searched: true } })
    expect(wrapper.text()).toContain('没有命中')
  })

  it('展示命中内容、相似度与来源位置', () => {
    const wrapper = mount(HitList, { props: { hits: [makeHit()], searched: true } })
    expect(wrapper.text()).toContain('边检周期为每周一次')
    // 相似度保留 4 位小数。
    expect(wrapper.text()).toContain('0.9312')
    expect(wrapper.text()).toContain('手册.txt')
    expect(wrapper.text()).toContain('字符 0–120')
  })

  it('缺少偏移量时只展示文件名', () => {
    const wrapper = mount(HitList, {
      props: {
        hits: [makeHit({ metadata: { file_name: '手册.txt' } })],
        searched: true,
      },
    })
    expect(wrapper.text()).toContain('手册.txt')
    expect(wrapper.text()).not.toContain('字符')
  })

  it('元数据缺失时不渲染出 undefined', () => {
    const wrapper = mount(HitList, {
      props: { hits: [makeHit({ metadata: {} })], searched: true },
    })
    expect(wrapper.text()).not.toContain('undefined')
  })
})
