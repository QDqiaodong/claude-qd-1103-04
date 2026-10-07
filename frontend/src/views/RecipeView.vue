<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { formatMoney, validatePriceInput } from '../utils/money'

const recipes = ref([])
const toppingOptions = ref([])   // 启用的「小料」原料
const checked = ref([])          // 勾选的小料名
const drawerVisible = ref(false)
const editing = ref(false)
// 售价用文本承载，避免 el-input-number 强制两位小数把用户输入静默四舍五入
const form = reactive({ id: null, version: null, name: '', baseTea: '', price: '' })
const formError = reactive({ name: '', baseTea: '', price: '', toppings: '' })
// 编辑历史遗留异常价时的降级提示（服务端原样返回，不做本地四舍五入）
const legacyPrice = ref(null)
const draftQty = reactive({})    // 小料名 -> 份数

const loading = ref(false)
const saving = ref(false)
let loadSeq = 0                  // 只读请求序号：快速连续/多页面交替时只认最后一次响应

async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const [recs, tops] = await Promise.all([
      api.listRecipes(),
      api.listIngredients('小料', '启用')
    ])
    if (seq !== loadSeq) return // 已有更新的读取，丢弃过期响应，避免新旧列表前后不一致
    recipes.value = recs
    toppingOptions.value = tops
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function resetForm() {
  form.id = null
  form.version = null
  form.name = ''
  form.baseTea = ''
  form.price = ''
  formError.name = ''
  formError.baseTea = ''
  formError.price = ''
  formError.toppings = ''
  legacyPrice.value = null
  checked.value = []
  for (const k in draftQty) delete draftQty[k]
}

function openCreate() {
  editing.value = false
  resetForm()
  form.price = '12.00'
  drawerVisible.value = true
}

// 编辑一律以服务端最新数据为准（防止列表停留期间其他页面已改过配方）
async function openEdit(row) {
  editing.value = true
  resetForm()
  form.id = row.id
  drawerVisible.value = true
  const savingNote = ElMessage.info({ message: '正在载入配方最新值…', duration: 0 })
  try {
    const latest = await api.getRecipe(row.id)
    if (!drawerVisible.value || form.id !== latest.id) return // 等待期间已切换/关闭
    fillForm(latest)
  } catch (e) {
    drawerVisible.value = false
    ElMessage.error(e.message)
  } finally {
    savingNote.close()
  }
}

function fillForm(r) {
  form.id = r.id
  form.version = r.version
  form.name = r.name
  form.baseTea = r.baseTea
  checked.value = Object.keys(r.toppings || {})
  for (const k in draftQty) delete draftQty[k]
  Object.entries(r.toppings || {}).forEach(([k, v]) => { draftQty[k] = v })
  if (r.priceStatus === 'ok') {
    // 规范价：重新载入后的规范值，两处小数
    form.price = formatMoney(r.price)
    legacyPrice.value = null
  } else {
    // 历史遗留空/多位小数价：不静默四舍五入回填，价格留空让用户显式确认，原始值单独提示
    form.price = ''
    legacyPrice.value = r
    formError.price = r.priceNote
  }
}

function onCheck(name, val) {
  if (val && draftQty[name] == null) draftQty[name] = 1
  formError.toppings = ''
}

async function submitDrawer() {
  if (saving.value) return // 重复点击 / 快速连点：在途保存直接忽略，保证最终状态唯一
  // 本地即时校验：失败只更新行内提示，不清空用户已输入的任何内容
  formError.name = form.name.trim() ? '' : '请填写配方名'
  formError.baseTea = form.baseTea.trim() ? '' : '请填写茶底'
  const priceCheck = validatePriceInput(form.price)
  formError.price = priceCheck.ok ? '' : priceCheck.error
  let toppings = {}
  if (!checked.value.length) {
    formError.toppings = '请至少勾选一种小料'
  } else {
    for (const name of checked.value) {
      const q = Number(draftQty[name])
      if (!Number.isInteger(q) || q <= 0) {
        formError.toppings = '小料「' + name + '」的份数必须为大于 0 的整数'
        toppings = {}
        break
      }
      toppings[name] = q
    }
  }
  if (formError.name || formError.baseTea || formError.price || formError.toppings) return

  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      baseTea: form.baseTea.trim(),
      price: priceCheck.value,
      toppings,
      version: editing.value ? form.version : undefined
    }
    if (editing.value) {
      const updated = await api.updateRecipe(form.id, payload)
      ElMessage.success('已更新配方，售价已按两位小数规范保存')
      form.version = updated.version
    } else {
      await api.createRecipe(payload)
      ElMessage.success('已生成配方')
    }
    drawerVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    // 失败保留抽屉、保留全部用户输入；版本过期时回填服务端最新版本，便于直接重试
    formError.price = /价格|售价|小数/.test(e.message) ? e.message : formError.price
    if (/版本|其他页面/.test(e.message) && form.id != null) {
      try {
        const latest = await api.getRecipe(form.id)
        form.version = latest.version
        ElMessage.warning(e.message + '（已载入最新版本，确认后可再次保存）')
      } catch (_) {
        ElMessage.error(e.message)
      }
    } else {
      ElMessage.error(e.message)
    }
  } finally {
    saving.value = false
  }
}

const emit = defineEmits(['changed'])

onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <div class="hint">勾选小料 → 右侧抽屉确认份数并生成配方；售价统一两位小数落库与展示</div>
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
          <el-table-column label="售价" width="150">
            <template #default="{ row }">
              <span v-if="row.priceStatus === 'ok'" class="price-ok">¥{{ formatMoney(row.price) }}</span>
              <el-tooltip v-else :content="row.priceNote" placement="top">
                <el-tag type="danger" size="small" class="price-bad">
                  {{ row.priceRaw == null ? '空价格' : ('¥' + String(row.priceRaw)) }}·待修复
                </el-tag>
              </el-tooltip>
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
        <el-form-item label="配方名" :error="formError.name">
          <el-input v-model="form.name" placeholder="如 招牌珍珠奶茶" @input="formError.name = ''" />
        </el-form-item>
        <el-form-item label="茶底" :error="formError.baseTea">
          <el-input v-model="form.baseTea" placeholder="如 红茶" @input="formError.baseTea = ''" />
        </el-form-item>
        <el-form-item label="售价" :error="formError.price">
          <el-input
            v-model="form.price"
            placeholder="单位：元，最多两位小数，如 12.00"
            @input="formError.price = ''"
          >
            <template #append>元</template>
          </el-input>
          <el-alert
            v-if="legacyPrice"
            class="legacy-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="legacyPrice.priceNote"
            :description="legacyPrice.priceRaw == null
              ? '该配方没有可计价的历史价格，请直接填写规范售价。'
              : '数据库中的原始值为 ¥' + String(legacyPrice.priceRaw) + '，系统未自动四舍五入。'"
          />
        </el-form-item>
        <el-form-item label="小料份数" :error="formError.toppings">
          <div v-if="checked.length" class="qty-list">
            <div v-for="name in checked" :key="name" class="qty-row">
              <span class="qty-name">{{ name }}</span>
              <el-input-number v-model="draftQty[name]" :min="1" :step="1" :precision="0" />
            </div>
          </div>
          <el-empty v-else description="请先在左侧勾选小料" :image-size="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitDrawer">
          {{ editing ? '保存修改' : '确认生成' }}
        </el-button>
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
.price-ok { font-weight: 600; color: #6b4423; }
.price-bad { cursor: help; }
.qty-list { display: flex; flex-direction: column; gap: 10px; width: 100%; }
.qty-row { display: flex; align-items: center; justify-content: space-between; }
.qty-name { font-weight: 500; }
.legacy-alert { margin-top: 8px; }
</style>
