<script setup lang="ts">
import { ref } from 'vue'
import { explain } from '../api/client'
import { ask } from '../api/chat'
import type { RetrievedChunk } from '../api/types'

/**
 * 智能体问答面板。
 *
 * <p>回答与引用切片一并展示 —— 引用由后端在检索命中时收集，
 * 不从回答文本反推。
 */

/** 提问内容。 */
const question = ref('')

/** 是否正在提问。 */
const loading = ref(false)

/** 智能体的回答。 */
const answer = ref('')

/** 本次回答引用的切片。 */
const citations = ref<RetrievedChunk[]>([])

/** 提问失败的错误说明。 */
const errorMessage = ref('')

/** 是否问过。 */
const asked = ref(false)

/** 发起提问。 */
async function onSend(): Promise<void> {
  if (!question.value.trim()) {
    errorMessage.value = '请输入问题。'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await ask(question.value.trim())
    answer.value = response.answer
    citations.value = response.citations
    asked.value = true
  } catch (error) {
    errorMessage.value = explain(error)
    answer.value = ''
    citations.value = []
    asked.value = true
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="chat">
    <div class="chat-head">
      <h3 class="section-title">智能体问答</h3>
      <p class="section-note">deepseek-v4.1-flash-1017</p>
    </div>

    <div class="chat-row">
      <input
        v-model="question"
        class="chat-input"
        type="text"
        placeholder="向知识库提问"
        aria-label="提问"
        @keyup.enter="onSend"
      />
      <button class="btn btn-primary" type="button" :disabled="loading" @click="onSend">
        {{ loading ? '思考中…' : '提问' }}
      </button>
    </div>

    <div v-if="loading" class="scan" aria-hidden="true"></div>
    <p v-if="errorMessage" class="chat-error">{{ errorMessage }}</p>

    <div v-if="asked && answer" class="chat-answer">
      <p class="chat-answer-text">{{ answer }}</p>
    </div>

    <div v-if="citations.length > 0" class="citations">
      <p class="citations-title">引用片段</p>
      <div v-for="citation in citations" :key="citation.chunkId" class="citation">
        <div class="citation-head">
          <span class="mono citation-score">{{ citation.score.toFixed(4) }}</span>
          <span class="mono citation-source">{{ citation.metadata.file_name }}</span>
        </div>
        <p class="citation-content">{{ citation.content }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.chat {
  background: var(--panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  padding: 16px;
}

.chat-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.chat-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.chat-input {
  flex: 1;
  background: var(--night);
  border: 1px solid var(--edge);
  border-radius: var(--radius);
  color: var(--ink);
  font-family: inherit;
  font-size: 13px;
  padding: 7px 10px;
}

.chat-error {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--alert);
}

.chat-answer {
  margin-top: 14px;
  padding: 12px 14px;
  background: var(--night);
  border-left: 2px solid var(--beacon);
  border-radius: 0 var(--radius) var(--radius) 0;
}

.chat-answer-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.citations {
  margin-top: 14px;
}

.citations-title {
  margin: 0 0 8px;
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 0.04em;
}

.citation {
  padding: 9px 12px;
  background: var(--panel-raised);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  margin-bottom: 6px;
}

.citation-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.citation-score {
  color: var(--beacon);
}

.citation-source {
  color: var(--ink-faint);
  word-break: break-all;
}

.citation-content {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--ink-muted);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
