<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage } from './api'

type EventSummary = { id: number; organizerId: number; title: string; category: string; status: string; reviewNote?: string; createdAt: string }
type Tier = { id: number; name: string; price: number; purchaseLimit: number }
type Performance = { id: number; name: string; startsAt: string; ticketTiers: Tier[] }
type EventDetail = EventSummary & { description: string; purchaseNotice: string; performances: Performance[] }
type PageResult<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

const pending = ref<EventSummary[]>([])
const events = ref<EventSummary[]>([])
const detail = ref<EventDetail | null>(null)
const query = reactive({ keyword: '', status: '', page: 0, size: 10, total: 0 })

async function loadPending() {
  pending.value = (await api.get<EventSummary[]>('/api/admin/events/pending')).data
}

async function loadEvents() {
  const { data } = await api.get<PageResult<EventSummary>>('/api/admin/events', {
    params: { keyword: query.keyword, status: query.status || undefined, page: query.page, size: query.size },
  })
  events.value = data.content
  query.total = data.totalElements
}

async function inspect(eventId: number) {
  detail.value = (await api.get<EventDetail>(`/api/admin/events/${eventId}`)).data
}

function searchEvents() { query.page = 0; loadEvents().catch(error => ElMessage.error(errorMessage(error))) }
function changePage(page: number) { query.page = page - 1; loadEvents().catch(error => ElMessage.error(errorMessage(error))) }

async function approve(eventId: number) {
  try {
    await api.post(`/api/admin/events/${eventId}/approve`)
    detail.value = null
    await Promise.all([loadPending(), loadEvents()])
    ElMessage.success('活动已审核通过并公开展示')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function reject(eventId: number) {
  try {
    const { value } = await ElMessageBox.prompt('请填写可操作的修改意见', '驳回活动', {
      inputPattern: /\S+/,
      inputErrorMessage: '审核意见不能为空',
    })
    await api.post(`/api/admin/events/${eventId}/reject`, { note: value })
    detail.value = null
    await Promise.all([loadPending(), loadEvents()])
    ElMessage.success('活动已驳回')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(errorMessage(error))
  }
}

async function offShelf(item: EventSummary) {
  try {
    const { value } = await ElMessageBox.prompt('请填写下架原因', `下架“${item.title}”`, {
      inputPattern: /\S+/,
      inputErrorMessage: '下架原因不能为空',
    })
    await api.post(`/api/admin/events/${item.id}/off-shelf`, { note: value })
    await loadEvents()
    ElMessage.success('活动已下架并停止新订单')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(errorMessage(error))
  }
}

onMounted(async () => {
  try { await Promise.all([loadPending(), loadEvents()]) }
  catch (error) { ElMessage.error(errorMessage(error)) }
})
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading">
      <div><span class="eyebrow">EVENT REVIEW</span><h2>活动审核与生命周期</h2></div>
      <span>{{ pending.length }} 个待审核</span>
    </div>
    <el-table :data="pending" empty-text="当前没有待审核活动">
      <el-table-column prop="title" label="活动" /><el-table-column prop="category" label="类别" width="130" />
      <el-table-column prop="organizerId" label="主办方 ID" width="120" />
      <el-table-column label="提交时间" width="200"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString() }}</template></el-table-column>
      <el-table-column label="操作" width="110"><template #default="scope"><el-button text type="primary" @click="inspect(scope.row.id)">审核</el-button></template></el-table-column>
    </el-table>

    <el-card shadow="never">
      <div class="panel-heading"><h3>活动生命周期</h3><span>{{ query.total }} 个活动</span></div>
      <div class="search-row">
        <el-input v-model="query.keyword" clearable placeholder="搜索活动名称" @keyup.enter="searchEvents" @clear="searchEvents" />
        <el-select v-model="query.status" clearable placeholder="全部状态" @change="searchEvents">
          <el-option v-for="status in ['APPROVED', 'ON_SALE', 'OFF_SHELF', 'ENDED', 'CANCELLED']" :key="status" :label="status" :value="status" />
        </el-select><el-button type="primary" @click="searchEvents">查询</el-button>
      </div>
      <el-table :data="events" empty-text="暂无活动">
        <el-table-column prop="title" label="活动" /><el-table-column prop="category" label="类别" width="130" />
        <el-table-column prop="status" label="状态" width="150" /><el-table-column prop="reviewNote" label="审核/下架说明" />
        <el-table-column label="操作" width="100"><template #default="scope">
          <el-button v-if="['APPROVED', 'ON_SALE'].includes(scope.row.status)" text type="danger" @click="offShelf(scope.row)">下架</el-button>
        </template></el-table-column>
      </el-table>
      <el-pagination v-if="query.total > query.size" background layout="prev, pager, next"
                     :current-page="query.page + 1" :page-size="query.size" :total="query.total" @current-change="changePage" />
    </el-card>

    <el-drawer :model-value="detail !== null" :title="detail?.title" size="520px" @close="detail = null">
      <template v-if="detail">
        <p>{{ detail.description }}</p>
        <el-alert :title="detail.purchaseNotice" type="info" :closable="false" />
        <h3>场次与票档</h3>
        <el-card v-for="item in detail.performances" :key="item.id" shadow="never" class="review-performance">
          <strong>{{ item.name }}</strong><p>{{ new Date(item.startsAt).toLocaleString() }}</p>
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
