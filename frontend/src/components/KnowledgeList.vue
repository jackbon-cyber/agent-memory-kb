<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { explain } from '../api/client'
import { deleteDocument, listDocuments } from '../api/knowledge'
import type { DocumentSummary } from '../api/types'

/**
 * 已入库文档列表。
 *
 * <p>上传成功后由父组件触发刷新 —— 列表是「读模型」，
 * 不自己猜测什么时候该更新。
 */

/** 文档列表。 */
const documents = ref<DocumentSummary[]>([])

/** 是否正在加载。 */
const loading = ref(false)

/** 加载失败的错误说明。 */
const errorMessage = ref('')

/** 正在删除的文档标识 —— 用于只禁用对应那一行的按钮。 */
const deletingId = ref('')

/** 拉取文档列表。 */
async function refresh(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    documents.value = await listDocuments()
  } catch (error) {
    errorMessage.value = explain(error)
  } finally {
    loading.value = false
  }
}

/** 删除一份文档。 */
async function onDelete(documentId: string): Promise<void> {
  deletingId.value = documentId
  errorMessage.value = ''
  try {
    await deleteDocument(documentId)
    await refresh()
  } catch (error) {
    errorMessage.value = explain(error)
  } finally {
    deletingId.value = ''
  }
}

onMounted(refresh)

/** 把刷新能力暴露给父组件。 */
defineExpose({ refresh })
</script>

<template>
  <section class="docs">
    <div class="docs-head">
      <h3 class="section-title">已入库文档</h3>
      <div class="docs-actions">
        <span class="section-note">{{ documents.length }} 份</span>
        <button class="btn btn-sm" type="button" :disabled="loading" @click="refresh">
          刷新
        </button>
      </div>
    </div>

    <div v-if="loading" class="scan" aria-hidden="true"></div>
    <p v-if="errorMessage" class="docs-error">{{ errorMessage }}</p>

    <p v-if="!loading && documents.length === 0" class="state">
      <p class="state-title">知识库还是空的</p>
      <p>上传一份 txt 或 xlsx 试试。</p>
    </p>

    <div v-else-if="documents.length > 0" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>文件名</th>
            <th>切片数</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="document in documents" :key="document.documentId">
            <td>{{ document.fileName }}</td>
            <td class="mono">{{ document.chunkCount }}</td>
            <td class="docs-cell-action">
              <button
                class="btn btn-sm"
                type="button"
                :disabled="deletingId === document.documentId"
                @click="onDelete(document.documentId)"
              >
                {{ deletingId === document.documentId ? '删除中…' : '删除' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.docs {
  background: var(--panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  padding: 16px;
}

.docs-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.docs-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.docs-cell-action {
  text-align: right;
  white-space: nowrap;
}

.docs-error {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--alert);
}
</style>
