<script setup lang="ts">
import type { RetrievedChunk } from '../api/types'

/**
 * 命中列表。
 *
 * <p>每条命中展示内容、相似度与来源位置 ——
 * 来源位置取自元数据的 `file_name` / `start_offset` / `end_offset`。
 */

/** 待展示的命中。 */
const props = defineProps<{
  hits: RetrievedChunk[]
  searched: boolean
}>()

/**
 * 读出元数据里的字符串值。
 *
 * <p>元数据来自 Chroma，值的类型不固定（可能是字符串、数字或布尔），
 * 因此这里做一次宽松读取 —— 拿不到就返回空串，
 * 而不是把 `unknown` 渲染到界面上。
 */
function text(metadata: Record<string, unknown>, key: string): string {
  const value = metadata[key]
  if (value === null || value === undefined) {
    return ''
  }
  return String(value)
}

/**
 * 渲染来源位置。
 *
 * <p>有偏移量就给出字符区间，没有就只给文件名 ——
 * 位置信息缺失时不编造。
 */
function location(hit: RetrievedChunk): string {
  const fileName = text(hit.metadata, 'file_name')
  const start = text(hit.metadata, 'start_offset')
  const end = text(hit.metadata, 'end_offset')
  if (start && end) {
    return `${fileName} · 字符 ${start}–${end}`
  }
  return fileName
}
</script>

<template>
  <section class="hits">
    <div class="hits-head">
      <h3 class="section-title">检索命中</h3>
      <p class="section-note">{{ props.hits.length }} 条</p>
    </div>

    <div v-if="props.hits.length === 0 && props.searched" class="state">
      <p class="state-title">没有命中</p>
      <p>换个说法，或放宽过滤条件再试一次。</p>
    </div>

    <div v-else-if="props.hits.length === 0" class="state">
      <p class="state-title">尚未检索</p>
      <p>输入查询内容后点「检索」。</p>
    </div>

    <div v-else class="hit-list">
      <article v-for="hit in props.hits" :key="hit.chunkId" class="hit">
        <div class="hit-head">
          <span class="mono hit-score">{{ hit.score.toFixed(4) }}</span>
          <span class="mono hit-location">{{ location(hit) }}</span>
        </div>
        <p class="hit-content">{{ hit.content }}</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.hits {
  background: var(--panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  padding: 16px;
}

.hits-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.hit-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  background: var(--edge-soft);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  overflow: hidden;
}

.hit {
  background: var(--panel-raised);
  padding: 11px 14px;
}

.hit-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.hit-score {
  color: var(--beacon);
}

.hit-location {
  color: var(--ink-faint);
  word-break: break-all;
}

.hit-content {
  margin: 7px 0 0;
  font-size: 13px;
  color: var(--ink);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
