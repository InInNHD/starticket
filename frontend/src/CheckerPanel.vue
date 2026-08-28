<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from './api'

const code = ref('')
const result = ref<{ ticketNo: string; result: string; checkedAt: string } | null>(null)

async function redeem() {
  try {
    result.value = (await api.post('/api/check-in/redeem', { code: code.value })).data
    ElMessage.success(result.value?.result === 'SUCCESS' ? '核销成功' : '已返回核销结果')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
</script>

<template>
  <section class="admin-panel checker-panel">
    <div class="panel-heading"><div><span class="eyebrow">CHECK IN</span><h2>电子票核销</h2></div></div>
    <el-card shadow="never">
      <el-form label-position="top" @submit.prevent="redeem">
        <el-form-item label="电子票码"><el-input v-model="code" type="textarea" :rows="4" placeholder="粘贴用户电子票码" /></el-form-item>
        <el-button native-type="submit" type="primary">核销</el-button>
      </el-form>
      <el-result v-if="result" :icon="result.result === 'SUCCESS' ? 'success' : 'warning'"
                 :title="result.result" :sub-title="result.ticketNo" />
    </el-card>
  </section>
</template>
