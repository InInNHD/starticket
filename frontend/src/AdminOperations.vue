<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from './api'

type DeadOutbox = { id: number; eventType: string; aggregateId: string; retryCount: number; lastError: string; createdAt: string }
type DeadMessage = { id: number; messageId: string; eventType: string; aggregateId: string; failureReason: string; failedAt: string }
type AuditLog = { id: number; actor: string; action: string; targetType: string; targetId: string; detail?: string; createdAt: string }
type PageResult<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

const outbox = ref<DeadOutbox[]>([])
const messages = ref<DeadMessage[]>([])
const audits = ref<AuditLog[]>([])
const auditPage = reactive({ page: 0, size: 10, total: 0 })

async function load() {
  const [outboxResponse, messageResponse, auditResponse] = await Promise.all([
    api.get<DeadOutbox[]>('/api/admin/outbox/dead'),
    api.get<DeadMessage[]>('/api/admin/messages/dead'),
    api.get<PageResult<AuditLog>>('/api/admin/audits', { params: { page: auditPage.page, size: auditPage.size } }),
  ])
  outbox.value = outboxResponse.data
  messages.value = messageResponse.data
  audits.value = auditResponse.data.content
  auditPage.total = auditResponse.data.totalElements
}

async function retryOutbox(id: number) {
  try { await api.post(`/api/admin/outbox/${id}/retry`); await load(); ElMessage.success('已重新进入发布队列') }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

async function replayMessage(id: number) {
  try { await api.post(`/api/admin/messages/dead/${id}/retry`); await load(); ElMessage.success('消费死信已重放') }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

function changeAuditPage(page: number) {
  auditPage.page = page - 1
  load().catch(error => ElMessage.error(errorMessage(error)))
}

onMounted(() => load().catch(error => ElMessage.error(errorMessage(error))))
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading"><div><span class="eyebrow">OPERATIONS</span><h2>消息可靠性与审计</h2></div></div>

    <el-card shadow="never">
      <div class="panel-heading"><h3>Outbox 发布失败</h3><span>{{ outbox.length }} 条</span></div>
      <el-table :data="outbox" empty-text="当前没有 Outbox 发布失败">
        <el-table-column prop="eventType" label="事件" /><el-table-column prop="aggregateId" label="业务标识" />
        <el-table-column prop="retryCount" label="重试" width="80" /><el-table-column prop="lastError" label="最后错误" />
        <el-table-column label="操作" width="100"><template #default="scope"><el-button text type="primary" @click="retryOutbox(scope.row.id)">重试发布</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <div class="panel-heading"><h3>RabbitMQ 消费死信</h3><span>{{ messages.length }} 条</span></div>
      <el-table :data="messages" empty-text="当前没有消费死信">
        <el-table-column prop="eventType" label="事件" /><el-table-column prop="aggregateId" label="业务标识" />
        <el-table-column prop="failureReason" label="失败原因" /><el-table-column label="失败时间" width="190">
          <template #default="scope">{{ new Date(scope.row.failedAt).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100"><template #default="scope"><el-button text type="primary" @click="replayMessage(scope.row.id)">重放</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <div class="panel-heading"><h3>关键操作审计</h3><span>{{ auditPage.total }} 条</span></div>
      <el-table :data="audits" empty-text="暂无审计记录">
        <el-table-column prop="actor" label="操作人" width="120" /><el-table-column prop="action" label="动作" width="180" />
        <el-table-column label="目标" min-width="160"><template #default="scope">{{ scope.row.targetType }} / {{ scope.row.targetId }}</template></el-table-column>
        <el-table-column prop="detail" label="说明" /><el-table-column label="时间" width="190">
          <template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString() }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="auditPage.total > auditPage.size" background layout="prev, pager, next"
                     :current-page="auditPage.page + 1" :page-size="auditPage.size" :total="auditPage.total"
                     @current-change="changeAuditPage" />
    </el-card>
  </section>
</template>
