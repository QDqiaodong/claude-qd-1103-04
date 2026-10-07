<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import IngredientView from './views/IngredientView.vue'
import RecipeView from './views/RecipeView.vue'
import MemberView from './views/MemberView.vue'
import CupOrderView from './views/CupOrderView.vue'

// ------------------------------------------------------------------
// 导航形态：顶部标签页（el-tabs），4 个业务模块各一个 tab。
// ------------------------------------------------------------------

const modules = [
  { path: '/ingredient', label: '原料库存', sub: '茶底 / 奶 / 小料 / 糖浆', comp: IngredientView },
  { path: '/recipe',     label: '配方出单', sub: '勾选小料 · 抽屉调份数', comp: RecipeView },
  { path: '/member',     label: '会员',     sub: '积分 · 档案合并 · 变更审计', comp: MemberView },
  { path: '/cup-order',  label: '出杯记录', sub: '微信 / 支付宝 / 现金', comp: CupOrderView }
]

const activeTab = ref(router.currentRoute.value.path)
const counts = ref({})

const currentView = computed(() => {
  const m = modules.find((mm) => mm.path === activeTab.value)
  return m ? m.comp : IngredientView
})

async function loadCounts() {
  try {
    const [ing, rec, mem, ord] = await Promise.all([
      api.listIngredients(), api.listRecipes(),
      api.listMembers(), api.listCupOrders()
    ])
    counts.value = {
      '/ingredient': ing.length,
      '/recipe': rec.length,
      '/member': mem.length,
      '/cup-order': ord.length
    }
  } catch (e) { /* 计数失败不影响主流程 */ }
}

function onTabChange(path) {
  router.push(path)
}

watch(() => router.currentRoute.value.path, (p) => { activeTab.value = p })

onMounted(async () => {
  activeTab.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">奶</span>
        <div class="brand-text">
          <div class="brand-title">奶茶饮品店 · 原料库存与配方出单</div>
          <div class="brand-sub">焦糖主题 · 顶部标签页导航 · 勾选小料 + 右侧抽屉出单</div>
        </div>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="nav-tabs" @tab-change="onTabChange">
      <el-tab-pane
        v-for="m in modules"
        :key="m.path"
        :name="m.path"
        :label="m.label"
      >
        <template #label>
          <span class="tab-label">
            {{ m.label }}
            <span class="tab-sub">{{ m.sub }}</span>
            <span v-if="counts[m.path] !== undefined" class="tab-count">{{ counts[m.path] }}</span>
          </span>
        </template>
      </el-tab-pane>
    </el-tabs>

    <main class="content">
      <component :is="currentView" @changed="loadCounts" />
    </main>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; display: flex; flex-direction: column; }

.topbar {
  display: flex; align-items: center; gap: 14px;
  padding: 0 22px; height: 64px;
  background: linear-gradient(100deg, var(--primary-deep) 0%, var(--primary) 60%, var(--primary-soft) 100%);
  color: #fff; box-shadow: 0 2px 12px rgba(154, 90, 48, 0.22);
}
.brand { display: flex; align-items: center; gap: 12px; }
.brand-mark {
  width: 38px; height: 38px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: #fff; color: var(--primary-deep); font-weight: 700; font-size: 20px;
}
.brand-title { font-size: 17px; font-weight: 600; }
.brand-sub { font-size: 12px; opacity: 0.85; }

.nav-tabs { padding: 0 22px; background: #fff; }
.tab-label { display: inline-flex; align-items: center; gap: 6px; font-size: 14px; }
.tab-sub { font-size: 11px; color: var(--muted); }
.tab-count {
  min-width: 20px; height: 20px; padding: 0 6px; border-radius: 10px;
  background: var(--primary); color: #fff; font-size: 12px;
  display: inline-flex; align-items: center; justify-content: center;
}

.content { flex: 1; padding: 18px 22px 28px; }
</style>
