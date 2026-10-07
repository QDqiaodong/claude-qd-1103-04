<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const list = ref([])
const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ id: null, name: '', category: '', stock: 0, unit: '', status: '启用' })
const filter = reactive({ category: '', status: '' })

const categories = ['茶底', '奶', '小料', '糖浆']
const statuses = ['启用', '停用']

async function load() {
  list.value = await api.listIngredients(
    filter.category || undefined,
    filter.status || undefined
  )
}

function resetForm() {
  form.id = null
  form.name = ''
  form.category = ''
  form.stock = 0
  form.unit = ''
  form.status = '启用'
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
      await api.updateIngredient(form.id, { ...form })
      ElMessage.success('已更新原料')
    } else {
      await api.createIngredient({ ...form })
      ElMessage.success('已新增原料')
    }
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const emit = defineEmits(['changed'])

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <div class="filters">
        <el-select v-model="filter.category" placeholder="类别" clearable @change="load" style="width:140px">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="filter.status" placeholder="状态" clearable @change="load" style="width:140px">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
      </div>
      <el-button type="primary" @click="openCreate">新增原料</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="原料名" />
      <el-table-column prop="category" label="类别" width="100" />
      <el-table-column prop="stock" label="库存" width="90" />
      <el-table-column prop="unit" label="单位" width="90" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === '启用' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑原料' : '新增原料'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="原料名">
          <el-input v-model="form.name" placeholder="如 珍珠" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category" style="width:100%">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="form.stock" :min="0" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" placeholder="如 份 / g / ml" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="启用">启用</el-radio>
            <el-radio value="停用">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.filters { display: flex; gap: 10px; }
</style>
