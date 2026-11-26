<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { explain, getScope, setScope } from './api/client'
import KnowledgeView from './views/KnowledgeView.vue'
import MemoryView from './views/MemoryView.vue'

/**
 * 控制台外壳。
 *
 * <p>左侧是视图导航，右侧是内容区，顶部一条健康状态条 ——
 * 值班员第一眼要看到的是「服务还活着吗」。
 */

/** 当前视图。 */
type View = 'knowledge' | 'memory'

const VIEWS: { key: View; label: string }[] = [
  { key: 'knowledge', label: '知识库' },
  { key: 'memory', label: '四层记忆' },
]

/** 当前选中的视图。 */
const activeView = ref<View>('knowledge')

/** 租户标识。 */
const tenantId = ref(getScope().tenantId)

/** 主体标识。 */
const ownerKey = ref(getScope().ownerKey)

/** 健康状态：组件名 → 状态。 */
const components = ref<Record<string, string>>({})

/** 整体健康状态。 */
const healthStatus = ref('UNKNOWN')

/** 健康检查的错误说明。 */
const healthError = ref('')

/**
 * 拉取健康状态。
 *
 * <p>探活失败不阻塞界面 —— 只是状态条显示 DOWN，
 * 值班员仍能进去操作并看到更具体的报错。
 */
async function refreshHealth(): Promise<void> {
  try {
    const response = await fetch('/api/health')
    const payload = (await response.json()) as {
      status: string
      components: Record<string, string>
    }
    healthStatus.value = payload.status
    components.value = payload.components
    healthError.value = ''
  } catch (error) {
    healthStatus.value = 'DOWN'
    healthError.value = explain(error)
  }
}

/** 应用作用域切换。 */
function applyScope(): void {
  setScope({ tenantId: tenantId.value, ownerKey: ownerKey.value })
}

onMounted(refreshHealth)
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <p class="brand-name">知识库控制台</p>
        <p class="brand-sub">AgentScope 2.0 × ChromaDB</p>
      </div>
      <button
        v-for="view in VIEWS"
        :key="view.key"
        class="nav-item"
        :class="{ 'is-active': activeView === view.key }"
        type="button"
        @click="activeView = view.key"
      >
        {{ view.label }}
      </button>
    </aside>

    <div class="main">
      <header class="duty-strip">
        <div class="duty-cell">
          <p class="duty-value" :class="healthStatus === 'UP' ? 'is-beacon' : 'is-signal'">
            {{ healthStatus }}
          </p>
          <p class="duty-label">整体状态</p>
        </div>
        <div v-for="(status, name) in components" :key="name" class="duty-cell">
          <p class="duty-value" :class="status === 'UP' ? 'is-beacon' : 'is-signal'">
            {{ status }}
          </p>
          <p class="duty-label">{{ name }}</p>
        </div>

        <div class="duty-role">
          <label for="tenant">租户</label>
          <input id="tenant" v-model="tenantId" class="scope-input" type="text" @change="applyScope" />
          <label for="owner">主体</label>
          <input id="owner" v-model="ownerKey" class="scope-input" type="text" @change="applyScope" />
          <button class="btn btn-sm" type="button" @click="refreshHealth">探活</button>
        </div>
      </header>

      <main class="content">
        <p v-if="healthError" class="health-error">{{ healthError }}</p>
        <KnowledgeView v-if="activeView === 'knowledge'" />
        <MemoryView v-else />
      </main>
    </div>
  </div>
</template>

<style scoped>
.scope-input {
  background: var(--panel-raised);
  color: var(--ink);
  border: 1px solid var(--edge);
  border-radius: var(--radius);
  padding: 5px 9px;
  font-family: var(--mono);
  font-size: 12px;
  width: 110px;
}

.health-error {
  margin: 0 0 14px;
  font-size: 12px;
  color: var(--alert);
}
</style>
