<script setup lang="ts">
import { ref, watch } from 'vue'
import type { FilterCondition, FilterLogic, FilterOperator } from '../api/types'

/**
 * 元数据过滤条件构造器。
 *
 * <p><b>取值一律按字符串输入</b>，不做自动类型嗅探 ——
 * 类型由操作符决定：`eq` 保持字符串（Chroma 的等值匹配类型敏感，
 * 字符串列必须传字符串），范围类操作符由后端解析为数值。
 * 自动嗅探会把「文件名恰好是数字」这类取值悄悄变成数值，反而制造静默零命中。
 *
 * <p><b>本地持有条件、向上抛变更</b>：组件内部维护一份条件副本，
 * 每次增删都同时更新本地副本并抛出 {@code change} 事件。
 * 之所以不直接改父组件传入的数组，是因为直接改 prop 的内容
 * 绕过了「父组件是唯一所有者」这一约定 ——
 * 父组件既要知道条件变了（才能在检索时带上新条件），
 * 也要保证自己那份数据不会被子组件悄悄改掉。
 */

/** 可选操作符，按语义顺序排列。 */
const OPERATORS: FilterOperator[] = ['eq', 'gt', 'gte', 'lt', 'lte']

/** 操作符的中文说明 —— 界面上比英文缩写好懂。 */
const OPERATOR_LABELS: Record<FilterOperator, string> = {
  eq: '等于',
  gt: '大于',
  gte: '大于等于',
  lt: '小于',
  lte: '小于等于',
}

/** 当前生效的过滤条件。 */
const props = defineProps<{
  filters: FilterCondition[]
  logic: FilterLogic
}>()

/** 条件变化时通知父组件。 */
const emit = defineEmits<{
  (event: 'change', filters: FilterCondition[], logic: FilterLogic): void
}>()

/** 本地条件副本 —— 组件内的增删改都作用在它上面。 */
const conditions = ref<FilterCondition[]>([...props.filters])

/** 本地组合逻辑副本。 */
const currentLogic = ref<FilterLogic>(props.logic)

/** 待添加条件的字段名。 */
const field = ref('')

/** 待添加条件的操作符。 */
const operator = ref<FilterOperator>('eq')

/** 待添加条件的取值。 */
const value = ref('')

/**
 * 父组件改了条件就同步过来。
 *
 * <p>例如「清空」按钮在别处被触发时，本地副本必须跟着变，
 * 否则界面会显示一份与实际生效条件不符的清单。
 */
watch(
  () => props.filters,
  (next) => {
    conditions.value = [...next]
  },
)

/** 字段名是否填了。 */
const canAdd = (): boolean => field.value.trim().length > 0

/** 把当前状态抛给父组件。 */
function notifyParent(): void {
  emit('change', [...conditions.value], currentLogic.value)
}

/** 添加一条过滤条件。 */
function addCondition(): void {
  if (!canAdd()) {
    return
  }
  conditions.value = [
    ...conditions.value,
    { field: field.value.trim(), operator: operator.value, value: value.value },
  ]
  field.value = ''
  value.value = ''
  notifyParent()
}

/** 删除一条过滤条件。 */
function removeCondition(index: number): void {
  conditions.value = conditions.value.filter((_, position) => position !== index)
  notifyParent()
}

/** 清空全部条件。 */
function clearConditions(): void {
  conditions.value = []
  field.value = ''
  value.value = ''
  notifyParent()
}

/** 切换组合逻辑。 */
function toggleLogic(): void {
  currentLogic.value = currentLogic.value === 'AND' ? 'OR' : 'AND'
  notifyParent()
}
</script>

<template>
  <section class="filters">
    <div class="filters-head">
      <h3 class="section-title">元数据过滤</h3>
      <p class="section-note">类型由操作符决定，无需手工声明</p>
    </div>

    <div class="filter-row">
      <input
        v-model="field"
        class="filter-field"
        type="text"
        placeholder="字段名，如 file_name"
        aria-label="过滤字段名"
      />
      <select v-model="operator" class="filter-operator" aria-label="过滤操作符">
        <option v-for="item in OPERATORS" :key="item" :value="item">
          {{ OPERATOR_LABELS[item] }}
        </option>
      </select>
      <input
        v-model="value"
        class="filter-value"
        type="text"
        placeholder="取值"
        aria-label="过滤取值"
      />
      <button class="btn" type="button" :disabled="!canAdd()" @click="addCondition">添加</button>
    </div>

    <div v-if="conditions.length > 0" class="filter-list">
      <div class="filter-list-head">
        <span class="mono">{{ conditions.length }} 个条件</span>
        <div class="filter-list-actions">
          <button class="btn btn-sm" type="button" @click="toggleLogic">组合：{{ currentLogic }}</button>
          <button class="btn btn-sm" type="button" @click="clearConditions">清空</button>
        </div>
      </div>
      <div v-for="(item, index) in conditions" :key="`${item.field}-${index}`" class="filter-item">
        <span class="mono">
          {{ item.field }} {{ OPERATOR_LABELS[item.operator] }} {{ item.value }}
        </span>
        <button
          class="btn btn-sm"
          type="button"
          :aria-label="`删除条件 ${item.field}`"
          @click="removeCondition(index)"
        >
          删除
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.filters {
  background: var(--panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  padding: 16px;
}

.filters-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.filter-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}

.filter-field,
.filter-value,
.filter-operator {
  background: var(--night);
  border: 1px solid var(--edge);
  border-radius: var(--radius);
  color: var(--ink);
  font-family: inherit;
  font-size: 12px;
  padding: 6px 9px;
}

.filter-field {
  flex: 1 1 160px;
  min-width: 140px;
}

.filter-value {
  flex: 1 1 120px;
  min-width: 110px;
}

.filter-list {
  margin-top: 14px;
  border-top: 1px solid var(--edge-soft);
  padding-top: 12px;
}

.filter-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  color: var(--ink-faint);
  font-size: 12px;
}

.filter-list-actions {
  display: flex;
  gap: 6px;
}

.filter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 5px 0;
  color: var(--ink-muted);
}
</style>
