<script setup lang="ts">
import { ref } from 'vue'
import ChatPanel from '../components/ChatPanel.vue'
import DocumentUpload from '../components/DocumentUpload.vue'
import FilterBuilder from '../components/FilterBuilder.vue'
import HitList from '../components/HitList.vue'
import KnowledgeList from '../components/KnowledgeList.vue'
import SearchPanel from '../components/SearchPanel.vue'
import type { FilterCondition, FilterLogic, RetrievedChunk } from '../api/types'

/**
 * 知识库视图。
 *
 * <p>把上传、过滤、检索、问答与文档清单组合成一条完整链路：
 * 上传 → 构造过滤条件 → 检索 → 查看命中 → 向智能体提问。
 */

/** 过滤条件，由 {@code FilterBuilder} 维护。 */
const filters = ref<FilterCondition[]>([])

/** 条件组合逻辑。 */
const logic = ref<FilterLogic>('AND')

/** 文档列表组件的实例，用于在上传后刷新。 */
const knowledgeList = ref<InstanceType<typeof KnowledgeList> | null>(null)

/** 检索命中的结果。 */
const hits = ref<RetrievedChunk[]>([])

/** 是否已经检索过一次 —— 用来区分「没搜过」与「搜了但没结果」。 */
const searched = ref(false)

/** 检索耗时（服务端实测）。 */
const tookMs = ref<number | null>(null)

/**
 * 过滤条件变化后清空上一次的命中。
 *
 * <p>条件改了却还挂着旧命中，会让人以为新条件没生效。
 */
function onFiltersChanged(next: FilterCondition[], nextLogic: FilterLogic): void {
  filters.value = next
  logic.value = nextLogic
  hits.value = []
  searched.value = false
  tookMs.value = null
}

/** 检索成功：接收命中与耗时。 */
function onSearchResult(resultHits: RetrievedChunk[], elapsed: number): void {
  hits.value = resultHits
  tookMs.value = elapsed
  searched.value = true
}

/** 检索失败：清空命中，保留错误提示在检索面板内。 */
function onSearchFailed(): void {
  hits.value = []
  searched.value = true
  tookMs.value = null
}
</script>

<template>
  <div class="knowledge-view">
    <div class="view-head">
      <h2 class="view-title">知识库</h2>
      <p class="view-note">上传文档 → 元数据过滤 → 在线检索 → 智能体问答</p>
      <p v-if="tookMs !== null" class="view-latency mono">服务端耗时 {{ tookMs }} ms</p>
    </div>

    <div class="grid">
      <div class="column">
        <DocumentUpload @uploaded="knowledgeList?.refresh()" />
        <FilterBuilder
          :filters="filters"
          :logic="logic"
          @change="onFiltersChanged"
        />
        <SearchPanel
          :filters="filters"
          :logic="logic"
          @result="onSearchResult"
          @failed="onSearchFailed"
        />
        <HitList :hits="hits" :searched="searched" />
      </div>

      <div class="column">
        <ChatPanel />
        <KnowledgeList ref="knowledgeList" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-view {
  display: flex;
  flex-direction: column;
  gap: var(--gap);
}

.view-head {
  display: flex;
  align-items: baseline;
  gap: 14px;
  flex-wrap: wrap;
}

.view-title {
  font-size: 17px;
  font-weight: 600;
  margin: 0;
}

.view-note {
  margin: 0;
  font-size: 12px;
  color: var(--ink-faint);
}

.view-latency {
  margin: 0 0 0 auto;
  color: var(--beacon);
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: var(--gap);
  align-items: start;
}

.column {
  display: flex;
  flex-direction: column;
  gap: var(--gap);
  min-width: 0;
}

@media (max-width: 1080px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
