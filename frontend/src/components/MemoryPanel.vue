<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { explain } from '../api/client'
import { getEpisodic, getLongTerm, getShortTerm, getWorking } from '../api/memory'
import type {
  Episode,
  MemoryRecord,
  ShortTermMemoryResponse,
  TaskItem,
} from '../api/types'

/**
 * 四层记忆面板。
 *
 * <p>四个页签分别展示短期、长期、工作、情景记忆。
 * 每个页签按需加载 —— 打开一个就拉一次，
 * 不在初始化时把所有内存一次性取回来。
 */

/** 当前页签。 */
type Tab = 'short' | 'long' | 'working' | 'episodic'

const TABS: { key: Tab; label: string }[] = [
  { key: 'short', label: '短期记忆' },
  { key: 'long', label: '长期记忆' },
  { key: 'working', label: '工作记忆' },
  { key: 'episodic', label: '情景记忆' },
]

/** 当前选中的页签。 */
const activeTab = ref<Tab>('short')

/** 短期记忆消息序列。 */
const messages = ref<string[]>([])

/** 长期记忆记录。 */
const records = ref<MemoryRecord[]>([])

/** 工作记忆任务。 */
const tasks = ref<TaskItem[]>([])

/** 情景记忆事件。 */
const episodes = ref<Episode[]>([])

/** 加载失败的错误说明。 */
const errorMessage = ref('')

/** 是否正在加载。 */
const loading = ref(false)

/** 切换页签并按需加载数据。 */
async function selectTab(tab: Tab): Promise<void> {
  activeTab.value = tab
  errorMessage.value = ''
  loading.value = true
  try {
    if (tab === 'short') {
      const response: ShortTermMemoryResponse = await getShortTerm()
      messages.value = response.messages
    } else if (tab === 'long') {
      records.value = (await getLongTerm()).records
    } else if (tab === 'working') {
      tasks.value = (await getWorking()).tasks
    } else {
      episodes.value = (await getEpisodic()).episodes
    }
  } catch (error) {
    errorMessage.value = explain(error)
  } finally {
    loading.value = false
  }
}

/** 首次进入时加载第一个页签。 */
onMounted(() => {
  void selectTab('short')
})

/** 把时间戳渲染成可读时刻。 */
function formatTime(epochMillis: number): string {
  if (!epochMillis) {
    return '—'
  }
  return new Date(epochMillis).toLocaleString('zh-CN')
}
</script>

<template>
  <section class="memory">
    <div class="memory-head">
      <h3 class="section-title">四层记忆</h3>
      <p class="section-note">按租户与主体隔离</p>
    </div>

    <div class="tabs" role="tablist">
      <button
        v-for="tab in TABS"
        :key="tab.key"
        class="tab"
        :class="{ 'is-active': activeTab === tab.key }"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.key"
        @click="selectTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <div v-if="loading" class="scan" aria-hidden="true"></div>
    <p v-if="errorMessage" class="memory-error">{{ errorMessage }}</p>

    <!-- 短期记忆：会话内的消息序列 -->
    <div v-if="activeTab === 'short'" class="memory-body">
      <p v-if="messages.length === 0" class="state">
        <p class="state-title">暂无短期记忆</p>
        <p>先在「问答」里提一个问题，会话上下文就会出现在这里。</p>
      </p>
      <ol v-else class="message-list">
        <li v-for="(message, index) in messages" :key="index" class="message">{{ message }}</li>
      </ol>
    </div>

    <!-- 长期记忆：带权重的持久记忆 -->
    <div v-if="activeTab === 'long'" class="memory-body">
      <p v-if="records.length === 0" class="state">
        <p class="state-title">暂无长期记忆</p>
        <p>智能体检索到的稳定事实会被沉淀到这里。</p>
      </p>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>内容</th>
              <th>权重</th>
              <th>作用域</th>
              <th>写入时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in records" :key="record.id">
              <td>{{ record.content }}</td>
              <td class="mono">{{ record.weight.toFixed(2) }}</td>
              <td class="mono">{{ record.scope.tenantId }} / {{ record.scope.ownerKey }}</td>
              <td class="mono">{{ formatTime(record.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 工作记忆：当前任务清单 -->
    <div v-if="activeTab === 'working'" class="memory-body">
      <p v-if="tasks.length === 0" class="state">
        <p class="state-title">暂无工作任务</p>
        <p>工作记忆承载执行中的任务与状态。</p>
      </p>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>任务</th>
              <th>状态</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.id">
              <td>{{ task.description }}</td>
              <td>
                <span class="status" :class="task.status === 'DONE' ? 'is-beacon' : 'is-signal'">
                  {{ task.status }}
                </span>
              </td>
              <td class="mono">{{ formatTime(task.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 情景记忆：发生过的事件流 -->
    <div v-if="activeTab === 'episodic'" class="memory-body">
      <p v-if="episodes.length === 0" class="state">
        <p class="state-title">暂无情景记忆</p>
        <p>情景记忆记录「发生过什么」，按事件流组织。</p>
      </p>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>类型</th>
              <th>事件</th>
              <th>作用域</th>
              <th>发生时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="episode in episodes" :key="episode.id">
              <td class="mono">{{ episode.type }}</td>
              <td>
                <span v-for="(event, index) in episode.events" :key="index" class="event">{{ event }}</span>
              </td>
              <td class="mono">{{ episode.scope.tenantId }} / {{ episode.scope.ownerKey }}</td>
              <td class="mono">{{ formatTime(episode.occurredAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.memory {
  background: var(--panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--radius);
  padding: 16px;
}

.memory-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.tabs {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--edge-soft);
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.tab {
  font-family: inherit;
  font-size: 13px;
  color: var(--ink-muted);
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  padding: 7px 14px;
  cursor: pointer;
}

.tab:hover {
  color: var(--ink);
}

.tab.is-active {
  color: var(--ink);
  border-bottom-color: var(--signal);
}

.memory-body {
  margin-top: 12px;
}

.message-list {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message {
  font-size: 13px;
  color: var(--ink-muted);
  line-height: 1.6;
  word-break: break-word;
}

.event {
  display: block;
  font-size: 12px;
  color: var(--ink-muted);
}

.memory-error {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--alert);
}
</style>
