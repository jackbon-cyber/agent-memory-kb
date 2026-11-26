<script setup lang="ts">
import { ref } from 'vue'
import { explain } from '../api/client'
import { search } from '../api/knowledge'
import type { FilterCondition, FilterLogic, RetrievedChunk } from '../api/types'

/**
 * 检索面板。
 *
 * <p>负责收集查询文本与条数上限，调用检索接口，
 * 并把命中通过事件抛给父组件渲染。
 *
 * <p>过滤条件以 <b>props</b> 传入而非方法入参：过滤条件由
 * {@code FilterBuilder} 维护，props 是响应式的，
 * 检索时读到的一定是最新值 —— 不会出现「界面上的条件变了但检索还按旧的来」。
 */

/** 当前生效的过滤条件。 */
const props = defineProps<{
  filters: FilterCondition[]
  logic: FilterLogic
}>()

/** 检索完成后把命中与耗时抛给父组件。 */
const emit = defineEmits<{
  (event: 'result', hits: RetrievedChunk[], tookMs: number): void
  (event: 'failed'): void
}>()

/** 是否正在检索。 */
const loading = ref(false)

/** 检索失败的错误说明。 */
const errorMessage = ref('')

/** 查询文本。 */
const query = ref('')

/** 返回条数上限。 */
const topK = ref(10)

/** 相似度下限，留空表示不施加下限。 */
const scoreThreshold = ref('')

/**
 * 执行一次检索。
 *
 * <p>过滤条件与组合逻辑取自 props —— 此刻的值就是界面上看到的值。
 */
async function onSearch(): Promise<void> {
  if (!query.value.trim()) {
    errorMessage.value = '请输入查询内容。'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const threshold = scoreThreshold.value.trim()
    const response = await search({
      query: query.value.trim(),
      filters: props.filters,
      logic: props.logic,
      topK: topK.value,
      ...(threshold ? { scoreThreshold: Number(threshold) } : {}),
    })
    emit('result', response.hits, response.tookMs)
  } catch (error) {
    errorMessage.value = explain(error)
    emit('failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="search-panel">
    <div class="search-head">
      <h3 class="section-title">语义检索</h3>
      <p class="section-note">耗时为服务端实测值</p>
    </div>

    <div class="search-row">
      <input
        v-model="query"
        class="search-query"
        type="text"
        placeholder="输入要检索的内容"
        aria-label="检索查询"
      />
      <label class="search-label">
        条数
        <input v-model.number="topK" class="search-topk" type="number" min="1" max="50" />
      </label>
      <label class="search-label">
        相似度下限
        <input
          v-model="scoreThreshold"
          class="search-threshold"
          type="number"
          step="0.05"
          min="0"
          max="1"
          placeholder="不限"
        />
      </label>
      <button class="btn btn-primary" type="button" :disabled="loading" @click="onSearch">
        {{ loading ? '检索中…' : '检索' }}
      </button>
    </div>

    <div v-if="loading" class="scan" aria-hidden="true"></div>
    <p v-if="errorMessage" class="search-error">{{ errorMessage }}</p>
  </section>
</template>

<style scoped>
.search-panel {
  background: var(--panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  padding: 16px;
}

.search-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.search-row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.search-query {
  flex: 1 1 240px;
  min-width: 200px;
  background: var(--night);
  border: 1px solid var(--edge);
  border-radius: var(--radius);
  color: var(--ink);
  font-family: inherit;
  font-size: 13px;
  padding: 7px 10px;
}

.search-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-faint);
}

.search-topk,
.search-threshold {
  width: 72px;
  background: var(--night);
  border: 1px solid var(--edge);
  border-radius: var(--radius);
  color: var(--ink);
  font-family: var(--mono);
  font-size: 12px;
  padding: 6px 8px;
}

.search-error {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--alert);
}
</style>
