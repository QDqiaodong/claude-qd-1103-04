<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import MemberProfileDialog from './MemberProfileDialog.vue'

const props = defineProps({
  operatorName: String,
  operatorRole: String
})
const emit = defineEmits(['changed'])

const list = ref([])
const filter = reactive({ level: '', status: '', keyword: '' })
const levels = ['普通', '银卡', '金卡']

const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ id: null, phone: '', name: '', points: 0, level: '普通' })

const contactVisible = ref(false)
const contactForm = reactive({ id: null, phone: '', name: '', originalPhone: '', originalName: '', reason: '' })

const detailVisible = ref(false)
const detailData = ref(null)

async function load() {
  list.value = await api.listMembers(filter.level || undefined, filter.status || undefined, filter.keyword || undefined)
}

function resetForm() {
  form.id = null
  form.phone = ''
  form.name = ''
  form.points = 0
  form.level = '普通'
}

function openCreate() {
  editing.value = false
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, row)
  dialogVisible.value = true
}

async function submit() {
  try {
    if (editing.value) {
      // 编辑弹窗只改姓名展示/积分/等级；手机号与姓名的正式修改走「变更联系方式」
      await api.updateMember(form.id, { points: form.points, level: form.level })
      ElMessage.success('已更新会员积分/等级')
    } else {
      await api.createMember({ phone: form.phone, name: form.name, points: form.points })
      ElMessage.success('已新增会员')
    }
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function adjustPoints(row) {
  try {
    const { value } = await ElMessageBox.prompt('输入积分变动（正数补录，负数冲减），需填写原因', '调整积分 · ' + row.name, {
      confirmButtonText: '下一步',
      inputPattern: /^-?\d+$/,
      inputErrorMessage: '请输入非零整数'
    })
    const delta = parseInt(value, 10)
    if (delta === 0) {
      ElMessage.warning('变动值不能为 0')
      return
    }
    const reasonResp = await ElMessageBox.prompt('请输入调整原因（将记入积分流水与审计）', '调整原因', {
      inputValidator: (v) => (v && v.trim() ? true : '调整原因不能为空')
    })
    await api.addMemberPoints(row.id, { delta, reason: reasonResp.value, operator: props.operatorName })
    ElMessage.success('积分已调整并记入流水')
    await load()
    emit('changed')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

function openContact(row) {
  Object.assign(contactForm, {
    id: row.id,
    phone: row.phone,
    name: row.name,
    originalPhone: row.phone,
    originalName: row.name,
    reason: ''
  })
  contactVisible.value = true
}

async function submitContact() {
  try {
    const payload = { reason: contactForm.reason, operator: props.operatorName }
    if (contactForm.phone !== contactForm.originalPhone) payload.phone = contactForm.phone
    if (contactForm.name !== contactForm.originalName) payload.name = contactForm.name
    if (!payload.phone && !payload.name) {
      ElMessage.info('手机号与姓名均未变化')
      contactVisible.value = false
      return
    }
    await api.changeMemberContact(contactForm.id, payload)
    ElMessage.success('联系方式已变更，前后值与原因已记录审计')
    contactVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openDetail(row) {
  try {
    detailData.value = await api.getMember(row.id)
    detailVisible.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function tagType(row) {
  if (row.alias) return 'warning'
  return row.level === '金卡' ? 'success' : (row.level === '银卡' ? 'primary' : 'info')
}

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <div class="filters">
        <el-input v-model="filter.keyword" placeholder="手机号 / 姓名（含已合并别名）搜索" clearable
                  style="width:280px" @keyup.enter="load" @clear="load" />
        <el-select v-model="filter.status" placeholder="档案状态" clearable style="width:140px" @change="load">
          <el-option label="有效" value="有效" />
          <el-option label="已合并（只读别名）" value="已合并" />
        </el-select>
        <el-select v-model="filter.level" placeholder="等级" clearable style="width:120px" @change="load">
          <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
        </el-select>
        <el-button @click="load">查询</el-button>
      </div>
      <el-button type="primary" @click="openCreate">新增会员</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="手机号" width="200">
        <template #default="{ row }">
          {{ row.phone }}
          <el-tag v-if="row.alias" size="small" type="warning" style="margin-left:4px">已合并别名</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="昵称" min-width="140">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.name }}</el-link>
          <el-tag v-if="row.alias" size="small" type="warning" style="margin-left:6px">主档 #{{ row.masterId }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="points" label="积分" width="90" sortable />
      <el-table-column prop="level" label="等级" width="100">
        <template #default="{ row }">
          <el-tag :type="tagType(row)">{{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="260">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">档案/审计</el-button>
          <el-button v-if="!row.alias" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="!row.alias" link type="primary" @click="openContact(row)">改手机号/姓名</el-button>
          <el-button v-if="!row.alias" link type="primary" @click="adjustPoints(row)">调积分</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增 / 编辑积分等级 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑会员（积分/等级）' : '新增会员'" width="440px">
      <el-form label-width="80px">
        <el-form-item label="手机号">
          <el-input v-model="form.phone" :disabled="editing" placeholder="11 位手机号" />
          <div v-if="editing" class="hint">手机号修改请使用「改手机号/姓名」，需填写原因并留审计</div>
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.name" :disabled="editing" />
          <div v-if="editing" class="hint">姓名修改请使用「改手机号/姓名」</div>
        </el-form-item>
        <el-form-item label="积分">
          <el-input-number v-model="form.points" :min="0" />
        </el-form-item>
        <el-form-item label="等级">
          <el-select v-model="form.level" style="width:100%">
            <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 联系方式变更 -->
    <el-dialog v-model="contactVisible" title="变更手机号 / 姓名（留审计）" width="460px">
      <el-form label-width="92px">
        <el-form-item label="会员">
          <el-tag>{{ contactForm.originalName }}（#{{ contactForm.id }}）</el-tag>
        </el-form-item>
        <el-form-item label="新手机号">
          <el-input v-model="contactForm.phone" :placeholder="contactForm.originalPhone" />
        </el-form-item>
        <el-form-item label="新姓名">
          <el-input v-model="contactForm.name" :placeholder="contactForm.originalName" />
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input v-model="contactForm.reason" type="textarea" :rows="2"
                    placeholder="如：顾客换号 / 登记错误更正（必填）" />
        </el-form-item>
      </el-form>
      <div class="hint">服务端将记录变更前后值、原因、生效时间与操作人；同一手机号不能绑定两个有效会员。</div>
      <template #footer>
        <el-button @click="contactVisible = false">取消</el-button>
        <el-button type="primary" @click="submitContact">确认变更</el-button>
      </template>
    </el-dialog>

    <MemberProfileDialog v-model="detailVisible" :data="detailData" />
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; gap: 12px; flex-wrap: wrap; }
.filters { display: flex; gap: 10px; flex-wrap: wrap; }
.hint { font-size: 12px; color: #a07a5a; line-height: 1.6; }
</style>
