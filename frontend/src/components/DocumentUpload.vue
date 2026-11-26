<script setup lang="ts">
import { computed, ref } from 'vue'
import { explain } from '../api/client'
import { uploadDocument } from '../api/knowledge'
import type { IngestionResponse } from '../api/types'

/**
 * 文档上传。
 *
 * <p>覆盖三条路径：未选文件（按钮禁用）、上传中（按钮禁用 + 状态提示）、
 * 上传完成（展示切片数）或失败（展示可读错误）。
 */

/** 支持的文件扩展名 —— 与后端 `DocumentController` 的白名单一致。 */
const ACCEPTED_EXTENSIONS = ['.txt', '.xlsx', '.xls']

/** 选中的文件。 */
const selectedFile = ref<File | null>(null)

/** 是否正在上传。 */
const uploading = ref(false)

/** 上传结果。 */
const result = ref<IngestionResponse | null>(null)

/** 上传失败的错误说明。 */
const errorMessage = ref('')

/** 是否可以提交 —— 选了文件且不在上传中。 */
const canSubmit = computed(() => selectedFile.value !== null && !uploading.value)

/** 上传成功时通知父组件刷新文档清单。 */
const emit = defineEmits<{
  (event: 'uploaded', result: IngestionResponse): void
}>()

/** 记下用户选中的文件。 */
function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  // 用下标而不是 `item(0)`：FileList 支持下标访问，
  // 而测试替身往往给的是普通数组 —— `item()` 在那种情况下并不存在。
  selectedFile.value = input.files?.[0] ?? null
  result.value = null
  errorMessage.value = ''
}

/** 上传选中的文件。 */
async function onSubmit(): Promise<void> {
  const file = selectedFile.value
  if (!file) {
    return
  }
  uploading.value = true
  errorMessage.value = ''
  result.value = null
  try {
    const ingested = await uploadDocument(file)
    result.value = ingested
    emit('uploaded', ingested)
  } catch (error) {
    errorMessage.value = explain(error)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section class="upload">
    <div class="upload-head">
      <h3 class="section-title">文档入库</h3>
      <p class="section-note">支持 .txt / .xlsx / .xls</p>
    </div>

    <div class="upload-row">
      <input
        class="file-input"
        type="file"
        :accept="ACCEPTED_EXTENSIONS.join(',')"
        @change="onFileChange"
      />
      <button class="btn btn-primary" type="button" :disabled="!canSubmit" @click="onSubmit">
        {{ uploading ? '入库中…' : '入库' }}
      </button>
    </div>

    <p v-if="selectedFile" class="upload-picked mono">{{ selectedFile.name }}</p>

    <div v-if="uploading" class="scan" aria-hidden="true"></div>

    <p v-if="result" class="upload-ok">
      <span class="mono">{{ result.documentId }}</span> 已入库，
      切分 <b class="mono">{{ result.chunkCount }}</b> 个片段，写入
      <b class="mono">{{ result.ingestedCount }}</b> 条。
    </p>

    <p v-if="errorMessage" class="upload-error">{{ errorMessage }}</p>
  </section>
</template>

<style scoped>
.upload {
  background: var(--panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  padding: 16px;
}

.upload-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.upload-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.file-input {
  font-family: inherit;
  font-size: 12px;
  color: var(--ink-muted);
}

.upload-picked {
  margin: 10px 0 0;
  color: var(--ink-muted);
  word-break: break-all;
}

.upload-ok {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--ink);
}

.upload-ok b {
  color: var(--beacon);
}

.upload-error {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--alert);
}
</style>
