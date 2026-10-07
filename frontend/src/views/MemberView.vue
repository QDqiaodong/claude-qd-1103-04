<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import MemberArchivePane from './member/MemberArchivePane.vue'
import MemberMergePane from './member/MemberMergePane.vue'

// 顶部身份：店员可发起合并，只有负责人能撤销合并
const operatorName = ref(localStorage.getItem('mt_operator') || '店员小美')
const operatorRole = ref(localStorage.getItem('mt_operator_role') || '店员')
const activePane = ref('archive')
const archiveRef = ref(null)
const mergeRef = ref(null)

const isOwner = computed(() => operatorRole.value === '负责人')

function onIdentityChange() {
  localStorage.setItem('mt_operator', operatorName.value)
  localStorage.setItem('mt_operator_role', operatorRole.value)
  ElMessage.success('已切换身份：' + operatorRole.value + ' · ' + operatorName.value)
}

function refreshAll() {
  archiveRef.value?.load()
  mergeRef.value?.load()
}

defineExpose({ refreshAll })

onMounted(() => {})
</script>

<template>
  <div>
    <div class="identity-bar">
      <span class="identity-label">当前操作人</span>
      <el-select v-model="operatorRole" style="width:110px" @change="onIdentityChange">
        <el-option label="店员" value="店员" />
        <el-option label="负责人" value="负责人" />
      </el-select>
      <el-input v-model="operatorName" style="width:160px" placeholder="操作人姓名" @change="onIdentityChange" />
      <el-tag v-if="isOwner" type="danger" size="small">可撤销合并</el-tag>
      <el-tag v-else type="info" size="small">仅负责人可撤销</el-tag>
    </div>

    <el-tabs v-model="activePane" class="member-tabs">
      <el-tab-pane label="会员档案" name="archive">
        <MemberArchivePane ref="archiveRef" :operator-name="operatorName" :operator-role="operatorRole" @changed="refreshAll" />
      </el-tab-pane>
      <el-tab-pane label="档案合并与审计" name="merge">
        <MemberMergePane ref="mergeRef" :operator-name="operatorName" :operator-role="operatorRole" @changed="refreshAll" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.identity-bar {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 12px; padding: 8px 12px;
  background: #fff8f2; border: 1px solid #f0d9c4; border-radius: 8px;
}
.identity-label { font-size: 13px; color: #8a5a3a; }
.member-tabs { background: transparent; }
</style>
