<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: Boolean,
  data: Object
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

const member = computed(() => props.data?.member)
const isAlias = computed(() => member.value?.alias)
const ledgerType = (t) => {
  if (t === '消费获得') return 'success'
  if (t === '退款回退') return 'danger'
  if (t === '手工调整') return 'warning'
  return 'info'
}
</script>

<template>
  <el-dialog v-model="visible" title="会员档案详情" width="880px" top="6vh">
    <div v-if="data && member">
      <el-descriptions :column="3" border size="small" class="block">
        <el-descriptions-item label="ID">{{ member.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="isAlias ? 'warning' : 'success'">{{ member.status }}</el-tag>
          <el-tag v-if="isAlias" type="warning" effect="plain" style="margin-left:6px">
            只读别名，主档 #{{ member.masterId }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="等级">{{ member.level }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ member.phone }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ member.name }}</el-descriptions-item>
        <el-descriptions-item label="积分余额">{{ member.points }}</el-descriptions-item>
        <el-descriptions-item v-if="isAlias" label="合并原因" :span="3">{{ member.mergeReason || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="isAlias" label="合并操作人">{{ member.mergedBy || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="isAlias" label="合并时间">{{ fmtTime(member.mergedAt) }}</el-descriptions-item>
        <el-descriptions-item label="建档时间">{{ fmtTime(member.createdAt) }}</el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="isAlias && data.master" type="warning" :closable="false" class="block"
                :title="`查询到的是已合并别名；历史订单与积分统一在主档：${data.master.name}（#${data.master.id}），手机号 ${data.master.phone}`" />

      <div v-if="data.aliases && data.aliases.length" class="block">
        <div class="section-title">名下只读别名（{{ data.aliases.length }}）</div>
        <el-table :data="data.aliases" border size="small">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="phone" label="原手机号" width="140" />
          <el-table-column prop="name" label="原姓名" width="160" />
          <el-table-column prop="mergeReason" label="合并原因" />
          <el-table-column prop="mergedBy" label="操作人" width="100" />
          <el-table-column label="合并时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.mergedAt) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="section-title">积分流水（合计应 = 余额 {{ member.points }}）</div>
      <el-table :data="data.ledgers" border size="small" max-height="240" class="block">
        <el-table-column prop="id" label="流水#" width="70" />
        <el-table-column label="变动" width="90">
          <template #default="{ row }">
            <span :style="{ color: row.changePoints >= 0 ? '#2e8b57' : '#d03050', fontWeight: 600 }">
              {{ row.changePoints >= 0 ? '+' : '' }}{{ row.changePoints }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="balanceAfter" label="变动后余额" width="100" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="ledgerType(row.type)">{{ row.type }}</el-tag>
            <el-tag v-if="row.originMemberId" size="small" type="warning" effect="plain" style="margin-left:4px">
              原属 #{{ row.originMemberId }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联" width="110">
          <template #default="{ row }">
            {{ row.refType ? `${row.refType}#${row.refId}` : '—' }}
            <span v-if="row.mergeRequestId">（合并单#{{ row.mergeRequestId }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="事由" min-width="140" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <div class="section-title">历史订单（{{ data.orders.length }}）</div>
      <el-table :data="data.orders" border size="small" max-height="220" class="block">
        <el-table-column prop="id" label="订单#" width="80" />
        <el-table-column prop="total" label="金额" width="90" />
        <el-table-column prop="payType" label="支付" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === '已退款' ? 'danger' : 'success'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="获得积分" width="90">
          <template #default="{ row }">{{ row.pointsEarned ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="下单时间">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <div class="section-title">联系方式变更审计（{{ data.contactAudits.length }}）</div>
      <el-table :data="data.contactAudits" border size="small" max-height="200" class="block">
        <el-table-column label="字段" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ row.field }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="oldValue" label="变更前" width="140" />
        <el-table-column prop="newValue" label="变更后" width="140" />
        <el-table-column prop="reason" label="原因" min-width="160" />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column label="生效时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.effectiveAt) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </el-dialog>
</template>

<style scoped>
.block { margin-bottom: 14px; }
.section-title { font-size: 13px; font-weight: 600; color: #8a5a3a; margin: 10px 0 6px; }
</style>
