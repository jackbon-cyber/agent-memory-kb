<script setup lang="ts">
import MemoryPanel from '../components/MemoryPanel.vue'

/**
 * 记忆视图。
 *
 * <p>四层记忆的查看入口 —— 短期、长期、工作、情景。
 */
</script>

<template>
  <div class="memory-view">
    <div class="view-head">
      <h2 class="view-title">四层记忆</h2>
      <p class="view-note">短期 · 长期 · 工作 · 情景</p>
    </div>

    <MemoryPanel />
  </div>
</template>

<style scoped>
.memory-view {
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
</style>
