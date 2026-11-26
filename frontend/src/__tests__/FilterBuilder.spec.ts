import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import FilterBuilder from '../components/FilterBuilder.vue'
import type { FilterCondition, FilterLogic } from '../api/types'

/**
 * 过滤条件构造器的测试。
 *
 * <p>重点钉住两条契约：
 * <ul>
 *   <li>添加与删除条件 —— 条件列表是父组件的输入，行为必须可预期；</li>
 *   <li><b>取值一律按字符串提交</b> —— 不做自动类型嗅探。
 *       类型由操作符决定，后端负责把范围类操作符的取值解析为数值。</li>
 * </ul>
 */

/** 挂载组件。 */
function mountBuilder() {
  return mount(FilterBuilder, {
    props: { filters: [], logic: 'AND' },
  })
}

/**
 * 从 change 事件里取出条件与逻辑。
 *
 * <p>事件携带的是<b>两个参数</b>：条件数组与组合逻辑，
 * 而不是打包成一个对象 —— 调用方按位置取用即可。
 */
function lastChange(wrapper: ReturnType<typeof mountBuilder>): {
  filters: FilterCondition[]
  logic: FilterLogic
} {
  const events = wrapper.emitted('change')
  const args = events?.at(-1) as unknown as [FilterCondition[], FilterLogic]
  return { filters: args[0], logic: args[1] }
}

/** 添加一条条件，便于测试列表内的操作。 */
async function addCondition(
  wrapper: ReturnType<typeof mountBuilder>,
  field: string,
  value: string,
): Promise<void> {
  await wrapper.get('input[aria-label="过滤字段名"]').setValue(field)
  await wrapper.get('input[aria-label="过滤取值"]').setValue(value)
  await wrapper.get('button.btn').trigger('click')
}

describe('FilterBuilder', () => {
  it('添加条件后出现在列表里', async () => {
    const wrapper = mountBuilder()
    await addCondition(wrapper, 'file_name', '手册.txt')

    expect(wrapper.text()).toContain('file_name')
    const change = lastChange(wrapper)
    expect(change.filters).toHaveLength(1)
    expect(change.filters[0]?.field).toBe('file_name')
    expect(change.filters[0]?.operator).toBe('eq')
  })

  it('取值保持字符串不做类型嗅探', async () => {
    const wrapper = mountBuilder()
    await addCondition(wrapper, 'sequence', '3')

    const change = lastChange(wrapper)
    // 即使取值看起来像数字，也必须是字符串 —— 类型由操作符决定。
    expect(typeof change.filters[0]?.value).toBe('string')
    expect(change.filters[0]?.value).toBe('3')
  })

  it('删除条件后从列表移除', async () => {
    const wrapper = mountBuilder()
    await addCondition(wrapper, 'file_name', '手册.txt')
    expect(lastChange(wrapper).filters).toHaveLength(1)

    await wrapper.get('button[aria-label="删除条件 file_name"]').trigger('click')

    expect(lastChange(wrapper).filters).toHaveLength(0)
  })

  it('字段名为空时不允许添加', async () => {
    const wrapper = mountBuilder()

    const addButton = wrapper.get('button.btn')
    expect(addButton.attributes('disabled')).toBeDefined()
    await addButton.trigger('click')

    expect(wrapper.emitted('change')).toBeFalsy()
  })

  it('组合逻辑可以在 AND 与 OR 之间切换', async () => {
    const wrapper = mountBuilder()
    await addCondition(wrapper, 'file_name', '手册.txt')

    // 条件列表头部第一个小按钮是「组合」切换。
    await wrapper.findAll('button.btn-sm')[0]?.trigger('click')

    expect(lastChange(wrapper).logic).toBe('OR')
  })

  it('清空按钮移除全部条件', async () => {
    const wrapper = mountBuilder()
    await addCondition(wrapper, 'file_name', '手册.txt')
    expect(lastChange(wrapper).filters).toHaveLength(1)

    // 按钮顺序：组合 → 清空 → 删除。
    await wrapper.findAll('button.btn-sm')[1]?.trigger('click')

    expect(lastChange(wrapper).filters).toHaveLength(0)
  })
})
