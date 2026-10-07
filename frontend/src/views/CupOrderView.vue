<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const list = ref([])
const failures = ref([])
const recipes = ref([])
const members = ref([])
const dialogVisible = ref(false)
const filter = reactive({ status: '' })

const payTypes = ['微信', '支付宝', '现金']
const statuses = ['已出杯', '已退款']

const draft = reactive({ memberId: null, payType: '微信', items: [] })

// 服务端实时预览（以最新库存为准）
const preview = ref(null)
const previewLoading = ref(false)
let previewTimer = null

const refundingId = ref(null)

const recipeMap = computed(() => {
  const m = {}
  recipes.value.forEach(r => { m[r.id] = r })
  return m
})
const memberMap = computed(() => {
  const m = {}
  members.value.forEach(x => { m[x.id] = x })
  return m
})

async function load() {
  const [ords, fails, recs, mems] = await Promise.all([
    api.listCupOrders(filter.status || undefined),
    api.listCupOrderFailures(),
    api.listRecipes(),
    api.listMembers()
  ])
  list.value = ords
  failures.value = fails
  recipes.value = recs
  members.value = mems
}

function memberText(memberId, fallback) {
  if (memberId == null) return '散客'
  const m = memberMap.value[memberId]
  return m ? m.name : (fallback || ('会员#' + memberId))
}

function itemsText(items) {
  if (!items || !items.length) return '—'
  return items.map(it => {
    const r = recipeMap.value[it.recipeId]
    return `${r ? r.name : ('配方#' + it.recipeId)}×${it.qty}`
  }).join('，')
}

function deductionText(row) {
  if (!row.deductions || !row.deductions.length) return '历史旧订单，无扣减快照'
  return row.deductions.map(d => {
    const state = d.refundQty != null
      ? `（已回补${d.refundQty}${d.unit}）`
      : `（扣减${d.usedQty}${d.unit}）`
    return `${d.ingredientName}${state}`
  }).join('，')
}

function openCreate() {
  draft.memberId = null
  draft.payType = '微信'
  draft.items = []
  preview.value = null
  dialogVisible.value = true
}

function addItem() {
  draft.items.push({ recipeId: recipes.value.length ? recipes.value[0].id : null, qty: 1 })
}

function removeItem(idx) {
  draft.items.splice(idx, 1)
}

// 明细/会员/支付任何变化 → 防抖请求服务端预览（合并计算、最新库存都在服务端）
watch(() => [draft.items, draft.memberId, draft.payType, dialogVisible.value], () => {
  if (!dialogVisible.value) return
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(fetchPreview, 250)
}, { deep: true })

async function fetchPreview() {
  if (!draft.items.length || draft.items.some(it => !it.recipeId || !it.qty || it.qty <= 0)) {
    preview.value = null
    return
  }
  previewLoading.value = true
  try {
    preview.value = await api.previewCupOrder({
      memberId: draft.memberId,
      payType: draft.payType,
      items: draft.items.map(it => ({ recipeId: it.recipeId, qty: it.qty }))
    })
  } catch (e) {
    preview.value = null
  } finally {
    previewLoading.value = false
  }
}

const submitLoading = ref(false)

async function submit() {
  try {
    if (!draft.items.length) {
      ElMessage.warning('请至少添加一种配方')
      return
    }
    if (draft.items.some(it => !it.recipeId || !it.qty || it.qty <= 0)) {
      ElMessage.warning('配方明细不合法')
      return
    }
    // 再次以服务端最新库存为准做一次预览，避免停留期间库存变化
    const latest = await api.previewCupOrder({
      memberId: draft.memberId,
      payType: draft.payType,
      items: draft.items.map(it => ({ recipeId: it.recipeId, qty: it.qty }))
    })
    if (!latest.feasible) {
      ElMessage.error('无法出杯：' + (latest.problems || []).join('；'))
      preview.value = latest
      return
    }
    submitLoading.value = true
    const payload = {
      memberId: draft.memberId,
      payType: draft.payType,
      items: draft.items.map(it => ({ recipeId: it.recipeId, qty: it.qty }))
    }
    await api.createCupOrder(payload)
    ElMessage.success('已生成出杯单，原料已按快照扣减')
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
    await load() // 失败原因已在服务端留痕，刷新失败记录区
  } finally {
    submitLoading.value = false
  }
}

async function refund(row) {
  if (refundingId.value != null) return
  refundingId.value = row.id
  try {
    const updated = await api.refundCupOrder(row.id)
    ElMessage.success(updated.refundNote || '已退款')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
    await load() // 并发/重复退款后与服务端状态对齐
  } finally {
    refundingId.value = null
  }
}

const emit = defineEmits(['changed'])

onMounted(load)
onBeforeUnmount(() => { if (previewTimer) clearTimeout(previewTimer) })
</script>

<template>
  <div>
    <div class="bar">
      <div class="filters">
        <el-select v-model="filter.status" placeholder="状态" clearable @change="load" style="width:160px">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
      </div>
      <el-button type="primary" @click="openCreate">生成出杯单</el-button>
    </div>

    <el-table :data="list" border stripe row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail-box">
            <div><span class="detail-k">配方明细：</span>{{ itemsText(row.items) }}</div>
            <div>
              <span class="detail-k">原料履约：</span>
              <span :class="{ 'snap-old': !row.deductions || !row.deductions.length }">{{ deductionText(row) }}</span>
            </div>
            <div v-if="row.memberId != null">
              <span class="detail-k">本次积分：</span>{{ row.pointsEarned ?? 0 }}
              <span v-if="row.status === '已退款'" class="rollback">（退款已回退）</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="单号" width="80" />
      <el-table-column label="会员" width="110">
        <template #default="{ row }">{{ memberText(row.memberId) }}</template>
      </el-table-column>
      <el-table-column prop="payType" label="支付" width="90" />
      <el-table-column label="配方明细" min-width="180">
        <template #default="{ row }">{{ itemsText(row.items) }}</template>
      </el-table-column>
      <el-table-column prop="total" label="金额" width="90" />
      <el-table-column label="积分" width="90">
        <template #default="{ row }">{{ row.memberId != null ? (row.pointsEarned ?? 0) : '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === '已出杯' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            v-if="row.status === '已出杯'"
            link type="danger"
            :loading="refundingId === row.id"
            @click="refund(row)"
          >退款</el-button>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 整单未落库的出杯失败记录 -->
    <div class="fail-section">
      <div class="fail-title">出杯失败记录（整单未扣库存、未加积分）</div>
      <el-table :data="failures" border stripe size="small">
        <el-table-column prop="id" label="编号" width="80" />
        <el-table-column label="会员" width="120">
          <template #default="{ row }">{{ row.memberId == null ? '散客' : (row.memberLabel || ('会员#' + row.memberId)) }}</template>
        </el-table-column>
        <el-table-column prop="payType" label="支付" width="90" />
        <el-table-column prop="requestItems" label="请求明细" min-width="180" />
        <el-table-column prop="reason" label="失败原因" min-width="240">
          <template #default="{ row }"><span class="fail-reason">{{ row.reason }}</span></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" title="生成出杯单" width="640px">
      <el-form label-width="80px">
        <el-form-item label="会员">
          <el-select v-model="draft.memberId" clearable placeholder="散客（不选）" style="width:100%">
            <el-option v-for="m in members" :key="m.id" :label="m.name + '（' + m.phone + '）'" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="支付">
          <el-radio-group v-model="draft.payType">
            <el-radio v-for="p in payTypes" :key="p" :value="p">{{ p }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="配方明细">
          <div class="items">
            <div v-for="(it, idx) in draft.items" :key="idx" class="item-row">
              <el-select v-model="it.recipeId" style="width:260px">
                <el-option v-for="r in recipes" :key="r.id" :label="r.name + '（¥' + r.price + '）'" :value="r.id" />
              </el-select>
              <el-input-number v-model="it.qty" :min="1" />
              <el-button link type="danger" @click="removeItem(idx)">删</el-button>
            </div>
            <el-button link type="primary" @click="addItem">+ 添加配方</el-button>
          </div>
        </el-form-item>

        <!-- 服务端实时预计扣减（同一原料跨配方/重复明细已合并） -->
        <el-form-item v-if="preview" label="预计扣减">
          <div class="preview-box" v-loading="previewLoading">
            <el-table :data="preview.demands" border size="small">
              <el-table-column prop="ingredientName" label="原料" min-width="100" />
              <el-table-column label="本单需求" width="110">
                <template #default="{ row }">{{ row.needQty }}{{ row.unit }}</template>
              </el-table-column>
              <el-table-column label="当前库存" width="110">
                <template #default="{ row }">{{ row.stock == null ? '—' : row.stock + row.unit }}</template>
              </el-table-column>
              <el-table-column label="校验" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.insufficient" type="danger" size="small">库存不足</el-tag>
                  <el-tag v-else-if="row.disabled" type="warning" size="small">停用/失效</el-tag>
                  <el-tag v-else type="success" size="small">充足</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <div class="preview-foot">
              <span>合计 ¥{{ preview.total }}</span>
              <span v-if="draft.memberId">预计积分 +{{ preview.pointsToEarn }}</span>
              <el-tag v-if="preview.feasible" type="success" size="small">库存满足，可出杯</el-tag>
            </div>
            <div v-if="preview.problems && preview.problems.length" class="preview-problems">
              <div v-for="(p, i) in preview.problems" :key="i" class="problem-line">⚠ {{ p }}</div>
            </div>
            <div class="preview-tip">预计结果仅供参考，提交时服务端仍以最新库存为准；任一原料不足/停用/失效则整单不落库。</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitLoading"
          :disabled="!preview || !preview.feasible"
          @click="submit"
        >确认出杯</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.filters { display: flex; gap: 10px; }
.items { display: flex; flex-direction: column; gap: 8px; }
.item-row { display: flex; align-items: center; gap: 10px; }

.detail-box { padding: 8px 20px; display: flex; flex-direction: column; gap: 6px; background: #faf6f1; }
.detail-k { color: #8a5a30; font-weight: 600; }
.snap-old { color: #a07d5a; }
.rollback { color: #909399; font-size: 12px; }

.fail-section { margin-top: 22px; }
.fail-title { font-weight: 600; margin-bottom: 10px; color: #b04a2f; }
.fail-reason { color: #c4563a; }

.preview-box { width: 100%; }
.preview-foot {
  display: flex; align-items: center; gap: 16px;
  margin-top: 8px; font-weight: 600; color: #6b4423;
}
.preview-problems { margin-top: 8px; }
.problem-line { color: #c4563a; font-size: 13px; line-height: 20px; }
.preview-tip { margin-top: 8px; font-size: 12px; color: #a07d5a; }
</style>
