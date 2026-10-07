<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { fmtMoney, priceProblem, recipePriceLabel } from '../utils/money'

const recipes = ref([])
const toppingOptions = ref([])   // 启用的「小料」原料
const checked = ref([])          // 勾选的小料名
const drawerVisible = ref(false)
const editing = ref(false)
const submitting = ref(false)    // 防重复点击：提交中禁用确认按钮
const form = reactive({ id: null, name: '', baseTea: '', price: '12.00' })
const draftQty = reactive({})    // 小料名 -> 份数

async function load() {
  const [recs, tops] = await Promise.all([
    api.listRecipes(),
    api.listIngredients('小料', '启用')
  ])
  recipes.value = recs
  toppingOptions.value = tops
}

function resetForm() {
  form.id = null
  form.name = ''
  form.baseTea = ''
  form.price = '12.00'
  checked.value = []
  for (const k in draftQty) delete draftQty[k]
}

function openCreate() {
  editing.value = false
  resetForm()
  drawerVisible.value = true
}

function openEdit(row) {
  editing.value = true
  form.id = row.id
  form.name = row.name
  form.baseTea = row.baseTea
  // 旧数据回显：异常售价原样回填（不静默四舍五入），正常售价显示两位小数规范值
  const label = recipePriceLabel(row)
  form.price = label.abnormal ? (row.price == null ? '' : String(row.price)) : label.text
  checked.value = Object.keys(row.toppings || {})
  for (const k in draftQty) delete draftQty[k]
  Object.entries(row.toppings || {}).forEach(([k, v]) => { draftQty[k] = v })
  drawerVisible.value = true
}

function onCheck(name, val) {
  if (val && draftQty[name] == null) draftQty[name] = 1
}

async function submitDrawer() {
  if (submitting.value) return   // 重复点击只受理一次
  try {
    if (!form.name) throw new Error('请填写配方名')
    if (!form.baseTea) throw new Error('请填写茶底')
    const priceErr = priceProblem(form.price)
    if (priceErr) throw new Error(priceErr)
    if (!checked.value.length) throw new Error('请至少勾选一种小料')
    const toppings = {}
    for (const name of checked.value) {
      const q = draftQty[name]
      if (!q || q <= 0) throw new Error('小料「' + name + '」的份数必须大于 0')
      toppings[name] = q
    }
    // 售价按用户输入原文提交，由服务端统一校验并规范化（前端不静默改写）
    const payload = { name: form.name, baseTea: form.baseTea, price: String(form.price).trim(), toppings }
    submitting.value = true
    if (editing.value) {
      await api.updateRecipe(form.id, payload)
      ElMessage.success('已更新配方')
    } else {
      await api.createRecipe(payload)
      ElMessage.success('已生成配方')
    }
    drawerVisible.value = false
    await load()   // 重新载入后列表展示服务端规范化后的售价
    emit('changed')
  } catch (e) {
    // 失败时抽屉保持打开、用户输入原样保留，错误提示来自服务端稳定文案
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

const emit = defineEmits(['changed'])

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <div class="hint">勾选小料 → 右侧抽屉确认份数并生成配方</div>
      <el-button type="primary" @click="openCreate">新增配方</el-button>
    </div>

    <div class="recipe-layout">
      <!-- 左：勾选小料 -->
      <section class="topping-panel">
        <div class="panel-title">小料（勾选后到右侧抽屉调份数）</div>
        <el-checkbox-group v-model="checked" @change="(vals) => vals.forEach(v => onCheck(v, true))">
          <el-checkbox
            v-for="t in toppingOptions"
            :key="t.id"
            :value="t.name"
            class="topping-chip"
          >{{ t.name }}</el-checkbox>
        </el-checkbox-group>
        <el-empty v-if="!toppingOptions.length" description="暂无可用的启用小料" :image-size="60" />
      </section>

      <!-- 右：配方列表 -->
      <section class="recipe-panel">
        <el-table :data="recipes" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="配方名" />
          <el-table-column prop="baseTea" label="茶底" width="100" />
          <el-table-column label="售价" width="120">
            <template #default="{ row }">
              <el-tooltip v-if="row.priceStatus === '异常'" :content="row.priceIssue" placement="top">
                <span class="price-abnormal">{{ row.price ?? '—' }} ⚠ 异常</span>
              </el-tooltip>
              <span v-else>¥{{ fmtMoney(row.price) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="小料用量">
            <template #default="{ row }">
              <el-tag v-for="(q, n) in row.toppings" :key="n" class="tp-tag" size="small">
                {{ n }}×{{ q }}
              </el-tag>
              <span v-if="!row.toppings || !Object.keys(row.toppings).length">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <!-- 右侧抽屉：确认份数并生成配方 -->
    <el-drawer v-model="drawerVisible" :title="editing ? '编辑配方' : '新增配方'" direction="rtl" size="420px">
      <el-form label-width="80px">
        <el-form-item label="配方名">
          <el-input v-model="form.name" placeholder="如 招牌珍珠奶茶" />
        </el-form-item>
        <el-form-item label="茶底">
          <el-input v-model="form.baseTea" placeholder="如 红茶" />
        </el-form-item>
        <el-form-item label="售价">
          <el-input v-model="form.price" placeholder="如 12.50（0.01 ~ 99999999.99，最多两位小数）" />
        </el-form-item>
        <el-form-item label="小料份数">
          <div v-if="checked.length" class="qty-list">
            <div v-for="name in checked" :key="name" class="qty-row">
              <span class="qty-name">{{ name }}</span>
              <el-input-number v-model="draftQty[name]" :min="1" />
            </div>
          </div>
          <el-empty v-else description="请先在左侧勾选小料" :image-size="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitDrawer">确认生成</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.hint { color: var(--muted); font-size: 13px; }
.recipe-layout { display: grid; grid-template-columns: 240px 1fr; gap: 16px; align-items: start; }
.topping-panel {
  background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 14px;
}
.panel-title { font-weight: 600; margin-bottom: 12px; }
.topping-chip { display: block; margin: 0 0 10px !important; }
.recipe-panel { min-width: 0; }
.tp-tag { margin: 0 6px 4px 0; }
.qty-list { display: flex; flex-direction: column; gap: 10px; }
.qty-row { display: flex; align-items: center; justify-content: space-between; }
.qty-name { font-weight: 500; }
.price-abnormal { color: #c4563a; font-weight: 600; cursor: help; }
</style>
