<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from './api'

type DeadEvent = { id: number; eventType: string; aggregateId: string; retryCount: number; lastError: string; createdAt: string }
const dead = ref<DeadEvent[]>([])
async function load() { dead.value = (await api.get<DeadEvent[]>('/api/admin/outbox/dead')).data }
async function retry(id: number) {
  try { await api.post(`/api/admin/outbox/${id}/retry`); await load(); ElMessage.success('已重新进入发布队列') }
  catch (error) { ElMessage.error(errorMessage(error)) }
}
onMounted(load)
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading"><div><span class="eyebrow">OPERATIONS</span><h2>消息异常</h2></div><span>{{ dead.length }} 条死信</span></div>
    <el-table :data="dead" empty-text="当前没有发布失败的关键消息">
      <el-table-column prop="eventType" label="事件" /><el-table-column prop="aggregateId" label="业务标识" />
      <el-table-column prop="retryCount" label="重试" width="80" /><el-table-column prop="lastError" label="最后错误" />
      <el-table-column label="操作" width="100"><template #default="scope"><el-button text type="primary" @click="retry(scope.row.id)">重试</el-button></template></el-table-column>
    </el-table>
  </section>
</template>
