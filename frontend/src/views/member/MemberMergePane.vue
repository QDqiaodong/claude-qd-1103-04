<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const props = defineProps({
  operatorName: String,
  operatorRole: String
})
const emit = defineEmits(['changed'])

const isOwner = computed(() => props.operatorRole === '负责人')

// ---------- 合并单列表 ----------
const list = ref([])
const statusFilter = ref('')
const statuses = ['待处理', '冲突待处理', '已合并', '已撤销']
const statusTag = { 待处理: 'primary', 冲突待处理: 'danger', 已合并: 'success', 已撤销: 'info' }
const statusCount = ref({})

async function load() {
  list.value = await api.listMemberMerges(statusFilter.value || undefined)
  const all = await api.listMemberMerges()
  const c = { '': all.length }
  for (const s of statuses) c[s] = all.filter((x) => x.status === s).length
  statusCount.value = c
}

// ---------- 会员选择（标记合并） ----------
const members = ref([])
const createVisible = ref(false)
const createForm = reactive({ masterId: null, aliasIds: [], reason: '' })

async function openCreate() {
  members.value = await api.listMembers(undefined, '有效', undefined)
  createForm.masterId = null
  createForm.aliasIds = []
  createForm.reason = ''
  previewData.value = null
  createVisible.value = true
}

const previewData = ref(null)
function onMasterChange() {
  createForm.aliasIds = createForm.aliasIds.filter((id) => id !== createForm.masterId)
  previewData.value = null
}

async function runPreview() {
  try {
    if (!createForm.masterId || !createForm.aliasIds.length) {
      ElMessage.warning('请先选择主档和至少一个待合并会员')
      return
    }
    previewData.value = await api.previewMemberMerge(createForm.masterId, createForm.aliasIds)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitCreate() {
  try {
    if (!createForm.masterId) return ElMessage.warning('请选择保留的主档')
    if (!createForm.aliasIds.length) return ElMessage.warning('请勾选待合并的重复会员')
    if (!createForm.reason.trim()) return ElMessage.warning('请填写合并原因')
    const res = await api.createMemberMerge({
      masterMemberId: createForm.masterId,
      aliasMemberIds: createForm.aliasIds,
      reason: createForm.reason,
      operator: props.operatorName
    })
    if (res.status === '冲突待处理') {
      ElMessage.warning('已标记，但检查发现冲突，合并单进入「冲突待处理」')
    } else {
      ElMessage.success('已标记为待合并')
    }
    createVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------- 执行 / 重新检查 ----------
async function execute(row) {
  try {
    await ElMessageBox.confirm(
      '合并将把副档的历史订单与积分流水统一归入主档，副档转为只读别名。确认执行？',
      '执行合并 · 合并单#' + row.id, { type: 'warning', confirmButtonText: '确认合并' })
    await api.executeMemberMerge(row.id, props.operatorName)
    ElMessage.success('合并成功：订单与积分已归主档')
    await load()
    emit('changed')
  } catch (e) {
    if (e === 'cancel') return
    // 冲突转入冲突待处理时后端返回业务错误
    ElMessage.error(e.message)
    await load()
  }
}

async function recheck(row) {
  try {
    await api.executeMemberMerge(row.id, props.operatorName)
    ElMessage.success('冲突已解决，合并成功')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
    await load()
  }
}

// ---------- 撤销（仅负责人） ----------
async function revoke(row) {
  if (!isOwner.value) {
    ElMessage.error('只有负责人可以撤销合并')
    return
  }
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '撤销将把订单与积分按来源恢复原归属（不重复计算积分）。请填写撤销原因',
      '负责人撤销合并 · 合并单#' + row.id, {
        confirmButtonText: '确认撤销',
        inputValidator: (v) => (v && v.trim() ? true : '撤销原因不能为空')
      })
    await api.revokeMemberMerge(row.id, {
      operator: props.operatorName,
      operatorRole: props.operatorRole,
      reason
    })
    ElMessage.success('已撤销合并，归属已恢复')
    await load()
    emit('changed')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

// ---------- 详情 ----------
const detailVisible = ref(false)
const detail = ref(null)
async function openDetail(row) {
  detail.value = await api.getMemberMerge(row.id)
  detailVisible.value = true
}

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

onMounted(load)
defineExpose({ load })
</script>

<template>
  <div>
    <div class="bar">
      <el-radio-group v-model="statusFilter" @change="load">
        <el-radio-button value="">全部 ({{ statusCount[''] ?? 0 }})</el-radio-button>
        <el-radio-button v-for="s in statuses" :key="s" :value="s">
          {{ s }} ({{ statusCount[s] ?? 0 }})
        </el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="openCreate">标记重复会员 / 发起合并</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="合并单#" width="90" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status]">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="主档" width="120">
        <template #default="{ row }">会员#{{ row.masterMemberId }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="合并原因" min-width="200" show-overflow-tooltip />
      <el-table-column label="冲突" min-width="200">
        <template #default="{ row }">
          <span v-if="row.conflictType" style="color:#d03050">
            <el-tag size="small" type="danger" effect="plain">{{ row.conflictType }}</el-tag>
            <div class="conflict-detail">{{ row.conflictDetail }}</div>
          </span>
          <span v-else style="color:#9aa0a6">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="发起人" width="100" />
      <el-table-column label="合并时间" width="170">
        <template #default="{ row }">{{ fmt(row.executedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情/审计</el-button>
          <el-button v-if="row.status === '待处理'" link type="success" @click="execute(row)">执行合并</el-button>
          <el-button v-if="row.status === '冲突待处理'" link type="warning" @click="recheck(row)">已处理，重新检查</el-button>
          <el-button v-if="row.status === '已合并'" link :type="isOwner ? 'danger' : 'info'"
                     @click="revoke(row)">{{ isOwner ? '撤销合并' : '撤销（仅负责人）' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建合并单 + 预览 -->
    <el-dialog v-model="createVisible" title="标记重复会员并选择保留的主档" width="820px" top="5vh">
      <el-form label-width="100px">
        <el-form-item label="保留主档">
          <el-select v-model="createForm.masterId" filterable placeholder="选择作为主档的会员"
                     style="width:100%" @change="onMasterChange">
            <el-option v-for="m in members" :key="m.id"
                       :label="`#${m.id} ${m.name}（${m.phone}）积分 ${m.points} / ${m.level}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="待合并副档">
          <el-select v-model="createForm.aliasIds" multiple filterable collapse-tags collapse-tags-tooltip
                     placeholder="勾选重复会员（可多个）" style="width:100%">
            <el-option v-for="m in members.filter((x) => x.id !== createForm.masterId)" :key="m.id"
                       :label="`#${m.id} ${m.name}（${m.phone}）积分 ${m.points}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="合并原因">
          <el-input v-model="createForm.reason" type="textarea" :rows="2"
                    placeholder="如：顾客同持两张卡，确认并入主档（必填）" />
        </el-form-item>
      </el-form>

      <el-button type="primary" plain style="margin:0 0 10px 100px" @click="runPreview">
        预览两边手机号/姓名/积分/历史订单
      </el-button>

      <div v-if="previewData" class="preview-box">
        <el-alert v-if="previewData.conflict" type="error" :closable="false" show-icon
                  title="执行前检查发现冲突，提交后将进入「冲突待处理」，不会静默覆盖"
                  :description="previewData.conflictDetail" />
        <el-alert v-else type="success" :closable="false" show-icon
                  :title="`检查通过：合并后主档预计积分 ${previewData.projectedPoints}（按积分流水合计）`" />
        <el-table :data="[previewData.master, ...previewData.aliases]" border size="small" style="margin-top:8px">
          <el-table-column label="角色" width="80">
            <template #default="{ row, $index }">
              <el-tag :type="$index === 0 ? 'success' : 'warning'">{{ $index === 0 ? '主档' : '副档' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="memberId" label="ID" width="70" />
          <el-table-column prop="phone" label="手机号" width="140" />
          <el-table-column prop="name" label="姓名" width="160" />
          <el-table-column prop="points" label="当前积分" width="90" />
          <el-table-column prop="ledgerSum" label="流水合计" width="90">
            <template #default="{ row }">
              <span :style="{ color: row.ledgerSum === row.points ? '#2e8b57' : '#d03050', fontWeight: 600 }">
                {{ row.ledgerSum }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="orderCount" label="历史订单数" width="90" />
          <el-table-column prop="level" label="等级" width="80" />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">标记为待合并</el-button>
      </template>
    </el-dialog>

    <!-- 合并单详情 -->
    <el-dialog v-model="detailVisible" title="合并单详情 · 合并前后差异与审计" width="900px" top="4vh">
      <div v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="合并单#">{{ detail.request.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag[detail.request.status]">{{ detail.request.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="主档">会员#{{ detail.request.masterMemberId }}</el-descriptions-item>
          <el-descriptions-item label="合并原因" :span="3">{{ detail.request.reason }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.request.createdBy }}</el-descriptions-item>
          <el-descriptions-item label="执行人">{{ detail.request.executedBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="合并时间">{{ fmt(detail.request.executedAt) }}</el-descriptions-item>
          <el-descriptions-item label="撤销人">{{ detail.request.revokedBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="撤销时间">{{ fmt(detail.request.revokedAt) }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmt(detail.request.createdAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.request.revokeReason" label="撤销原因" :span="3">
            {{ detail.request.revokeReason }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.request.conflictType" label="冲突类型">
            <el-tag type="danger" effect="plain">{{ detail.request.conflictType }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.request.conflictDetail" label="冲突明细" :span="2">
            <span class="conflict-detail">{{ detail.request.conflictDetail }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="section-title">成员合并前后差异</div>
        <el-table :data="detail.items" border size="small">
          <el-table-column label="角色" width="80">
            <template #default="{ row }">
              <el-tag :type="row.role === '主档' ? 'success' : 'warning'">{{ row.role }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="memberId" label="ID" width="70" />
          <el-table-column prop="snapshotPhone" label="合并前手机号" width="140" />
          <el-table-column prop="snapshotName" label="合并前姓名" width="160" />
          <el-table-column label="合并前积分" width="100">
            <template #default="{ row }">{{ row.snapshotPoints }}</template>
          </el-table-column>
          <el-table-column prop="snapshotOrderCount" label="合并前订单数" width="110" />
          <el-table-column prop="snapshotLevel" label="合并前等级" width="100" />
          <el-table-column label="合并后积分" width="110">
            <template #default="{ row }">
              <span v-if="row.finalPoints !== null && row.finalPoints !== undefined"
                    :style="{ fontWeight: 600, color: row.role === '主档' ? '#2e8b57' : '#d03050' }">
                {{ row.finalPoints }}
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="section-title">成员当前档案（撤销后为恢复值）</div>
        <el-table :data="detail.currentMembers" border size="small">
          <el-table-column prop="memberId" label="ID" width="70" />
          <el-table-column label="角色" width="80">
            <template #default="{ row }">
              <el-tag :type="row.role === '主档' ? 'success' : 'warning'">{{ row.role }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="phone" label="手机号" width="140" />
          <el-table-column prop="name" label="姓名" width="170" />
          <el-table-column prop="points" label="当前积分" width="90" />
          <el-table-column prop="level" label="等级" width="90" />
          <el-table-column label="状态/归属" min-width="160">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === '已合并' ? 'warning' : 'success'">{{ row.status }}</el-tag>
              <span v-if="row.mergedToId" style="margin-left:6px">→ 主档 #{{ row.mergedToId }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="section-title">历史订单归属迁移（{{ detail.orders.length }}）</div>
        <el-table :data="detail.orders" border size="small">
          <el-table-column prop="orderId" label="订单#" width="90" />
          <el-table-column label="原归属" width="90">
            <template #default="{ row }">会员#{{ row.fromMemberId }}</template>
          </el-table-column>
          <el-table-column label="目标" width="90">
            <template #default="{ row }">会员#{{ row.toMemberId }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === '已迁移' ? 'success' : 'info'">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="迁移时间" width="170">
            <template #default="{ row }">{{ fmt(row.migratedAt) }}</template>
          </el-table-column>
          <el-table-column label="恢复时间">
            <template #default="{ row }">{{ fmt(row.restoredAt) }}</template>
          </el-table-column>
        </el-table>

        <div class="section-title">联系方式审计（成员）</div>
        <el-table :data="detail.contactAudits" border size="small" max-height="160">
          <el-table-column label="会员" width="80">
            <template #default="{ row }">#{{ row.memberId }}</template>
          </el-table-column>
          <el-table-column prop="field" label="字段" width="80" />
          <el-table-column prop="oldValue" label="变更前" width="130" />
          <el-table-column prop="newValue" label="变更后" width="130" />
          <el-table-column prop="reason" label="原因" min-width="150" />
          <el-table-column prop="operator" label="操作人" width="100" />
          <el-table-column label="生效时间" width="170">
            <template #default="{ row }">{{ fmt(row.effectiveAt) }}</template>
          </el-table-column>
        </el-table>

        <div class="section-title">合并审计事件</div>
        <el-timeline>
          <el-timeline-item v-for="ev in detail.events" :key="ev.id"
                            :timestamp="fmt(ev.createdAt) + ' · ' + (ev.operator || '系统')"
                            :type="ev.event.includes('冲突') ? 'danger' : (ev.event.includes('撤销') ? 'info' : 'success')">
            <el-tag size="small" style="margin-right:8px">{{ ev.event }}</el-tag>
            {{ ev.detail }}
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; flex-wrap: wrap; gap: 10px; }
.conflict-detail { font-size: 12px; color: #d03050; margin-top: 4px; line-height: 1.5; }
.preview-box { margin: 4px 0 0 100px; }
.section-title { font-size: 13px; font-weight: 600; color: #8a5a3a; margin: 14px 0 6px; }
</style>
