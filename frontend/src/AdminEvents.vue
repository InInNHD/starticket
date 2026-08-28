<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage } from './api'

type EventSummary = { id: number; organizerId: number; title: string; category: string; status: string; createdAt: string }
type Tier = { id: number; name: string; price: number; purchaseLimit: number }
type Performance = { id: number; name: string; startsAt: string; ticketTiers: Tier[] }
type EventDetail = EventSummary & { description: string; purchaseNotice: string; performances: Performance[] }

const pending = ref<EventSummary[]>([])
const detail = ref<EventDetail | null>(null)

async function loadPending() {
  const { data } = await api.get<EventSummary[]>('/api/admin/events/pending')
  pending.value = data
}

async function inspect(eventId: number) {
  const { data } = await api.get<EventDetail>(`/api/admin/events/${eventId}`)
  detail.value = data
}

async function approve(eventId: number) {
  try {
    await api.post(`/api/admin/events/${eventId}/approve`)
    detail.value = null
    await loadPending()
    ElMessage.success('活动已审核通过并公开展示')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function reject(eventId: number) {
  try {
    const { value } = await ElMessageBox.prompt('请填写可操作的修改意见', '驳回活动', {
      inputPattern: /\S+/,
      inputErrorMessage: '审核意见不能为空',
    })
    await api.post(`/api/admin/events/${eventId}/reject`, { note: value })
    detail.value = null
    await loadPending()
    ElMessage.success('活动已驳回')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(errorMessage(error))
  }
}

onMounted(async () => {
  try {
    await loadPending()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
})
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading">
      <div><span class="eyebrow">EVENT REVIEW</span><h2>活动审核</h2></div>
      <span>{{ pending.length }} 个待审核</span>
    </div>
    <el-table :data="pending" empty-text="当前没有待审核活动">
      <el-table-column prop="title" label="活动" />
      <el-table-column prop="category" label="类别" width="130" />
      <el-table-column prop="organizerId" label="主办方 ID" width="120" />
      <el-table-column label="提交时间" width="200">
        <template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString() }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="scope"><el-button text type="primary" @click="inspect(scope.row.id)">审核</el-button></template>
      </el-table-column>
    </el-table>

    <el-drawer :model-value="detail !== null" :title="detail?.title" size="520px" @close="detail = null">
      <template v-if="detail">
        <p>{{ detail.description }}</p>
        <el-alert :title="detail.purchaseNotice" type="info" :closable="false" />
        <h3>场次与票档</h3>
        <el-card v-for="item in detail.performances" :key="item.id" shadow="never" class="review-performance">
          <strong>{{ item.name }}</strong>
          <p>{{ new Date(item.startsAt).toLocaleString() }}</p>
          <el-tag v-for="tier in item.ticketTiers" :key="tier.id">{{ tier.name }} · ¥{{ tier.price }} · 限购{{ tier.purchaseLimit }}张</el-tag>
        </el-card>
        <div class="review-actions">
          <el-button type="danger" plain @click="reject(detail.id)">驳回</el-button>
          <el-button type="success" @click="approve(detail.id)">审核通过</el-button>
        </div>
      </template>
    </el-drawer>
  </section>
</template>
