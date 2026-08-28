<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from './api'

type Venue = { id: number; name: string; city: string }
type Area = { id: number; name: string; seats: unknown[] }
type Layout = { venue: Venue; areas: Area[] }
type Tier = { id: number; name: string; price: number; color: string; purchaseLimit: number }
type Performance = { id: number; venueId: number; name: string; startsAt: string; ticketTiers: Tier[] }
type EventSummary = { id: number; title: string; category: string; status: string; reviewNote?: string }
type EventDetail = EventSummary & { description: string; purchaseNotice: string; performances: Performance[] }

const categoryOptions = [
  ['CONCERT', '演唱会'], ['THEATRE', '话剧'], ['EXHIBITION', '展览'],
  ['COMEDY', '喜剧'], ['CAMPUS', '校园活动'], ['OTHER', '其他'],
]
const events = ref<EventSummary[]>([])
const venues = ref<Venue[]>([])
const current = ref<EventDetail | null>(null)
const layout = ref<Layout | null>(null)
const loading = ref(false)
const draft = reactive({ title: '', category: 'CONCERT', description: '', posterUrl: '', purchaseNotice: '' })
const performance = reactive({ venueId: undefined as number | undefined, name: '', startsAt: '', salesStartAt: '', salesEndAt: '' })
const tier = reactive({ performanceId: undefined as number | undefined, areaId: undefined as number | undefined, name: '', price: 100, color: '#6B3BFF', purchaseLimit: 4 })

async function loadEvents() {
  const { data } = await api.get<EventSummary[]>('/api/organizer/events')
  events.value = data
}

async function openEvent(eventId: number) {
  const { data } = await api.get<EventDetail>(`/api/organizer/events/${eventId}`)
  current.value = data
}

async function createEvent() {
  try {
    loading.value = true
    const { data } = await api.post<EventDetail>('/api/organizer/events', draft)
    Object.assign(draft, { title: '', category: 'CONCERT', description: '', posterUrl: '', purchaseNotice: '' })
    current.value = data
    await loadEvents()
    ElMessage.success('活动草稿已创建')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function selectVenue(venueId: number) {
  performance.venueId = venueId
  tier.areaId = undefined
  await loadLayout(venueId)
}

async function loadLayout(venueId: number) {
  const { data } = await api.get<Layout>(`/api/organizer/venues/${venueId}/layout`)
  layout.value = data
}

async function addPerformance() {
  if (!current.value) return
  try {
    await api.post(`/api/organizer/events/${current.value.id}/performances`, {
      ...performance,
      startsAt: new Date(performance.startsAt).toISOString(),
      salesStartAt: new Date(performance.salesStartAt).toISOString(),
      salesEndAt: new Date(performance.salesEndAt).toISOString(),
    })
    Object.assign(performance, { venueId: undefined, name: '', startsAt: '', salesStartAt: '', salesEndAt: '' })
    layout.value = null
    await openEvent(current.value.id)
    ElMessage.success('场次已添加')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function choosePerformance(performanceId: number) {
  tier.performanceId = performanceId
  tier.areaId = undefined
  const selected = current.value?.performances.find(item => item.id === performanceId)
  if (selected) await loadLayout(selected.venueId)
}

async function addTier() {
  if (!current.value || !tier.performanceId) return
  try {
    const { performanceId, ...body } = tier
    await api.post(`/api/organizer/performances/${performanceId}/tiers`, body)
    Object.assign(tier, { areaId: undefined, name: '', price: 100, color: '#6B3BFF', purchaseLimit: 4 })
    await openEvent(current.value.id)
    ElMessage.success('票档已添加')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function submitReview() {
  if (!current.value) return
  try {
    const { data } = await api.post<EventDetail>(`/api/organizer/events/${current.value.id}/submit`)
    current.value = data
    await loadEvents()
    ElMessage.success('已提交管理员审核')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(async () => {
  try {
    const [, venueResponse] = await Promise.all([loadEvents(), api.get<Venue[]>('/api/organizer/venues')])
    venues.value = venueResponse.data
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
})
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading">
      <div><span class="eyebrow">ORGANIZER</span><h2>活动、场次与票档</h2></div>
      <span>{{ events.length }} 个活动</span>
    </div>

    <el-card shadow="never">
      <h3>1. 创建活动草稿</h3>
      <el-form :model="draft" label-position="top" @submit.prevent="createEvent">
        <div class="form-grid event-draft-grid">
          <el-form-item label="活动名称"><el-input v-model="draft.title" /></el-form-item>
          <el-form-item label="类别">
            <el-select v-model="draft.category">
              <el-option v-for="item in categoryOptions" :key="item[0]" :label="item[1]" :value="item[0]" />
            </el-select>
          </el-form-item>
          <el-form-item label="海报地址（可选）"><el-input v-model="draft.posterUrl" /></el-form-item>
        </div>
        <el-form-item label="活动介绍"><el-input v-model="draft.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="购票须知"><el-input v-model="draft.purchaseNotice" type="textarea" :rows="2" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="loading">保存草稿</el-button>
      </el-form>
    </el-card>

    <el-table :data="events" empty-text="还没有活动">
      <el-table-column prop="title" label="活动" />
      <el-table-column prop="category" label="类别" width="130" />
      <el-table-column prop="status" label="状态" width="160" />
      <el-table-column prop="reviewNote" label="审核意见" />
      <el-table-column label="操作" width="110">
        <template #default="scope"><el-button text type="primary" @click="openEvent(scope.row.id)">配置</el-button></template>
      </el-table-column>
    </el-table>

    <template v-if="current">
      <el-alert :title="`正在配置：${current.title}（${current.status}）`" type="info" :closable="false" />
      <el-card v-if="['DRAFT', 'REJECTED'].includes(current.status)" shadow="never">
        <h3>2. 添加演出场次</h3>
        <el-form :model="performance" label-position="top" class="form-grid performance-grid" @submit.prevent="addPerformance">
          <el-form-item label="场馆">
            <el-select :model-value="performance.venueId" @update:model-value="selectVenue" placeholder="选择场馆">
              <el-option v-for="venue in venues" :key="venue.id" :label="`${venue.name} · ${venue.city}`" :value="venue.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="场次名称"><el-input v-model="performance.name" placeholder="上海站 19:30" /></el-form-item>
          <el-form-item label="开售时间"><el-input v-model="performance.salesStartAt" type="datetime-local" /></el-form-item>
          <el-form-item label="停售时间"><el-input v-model="performance.salesEndAt" type="datetime-local" /></el-form-item>
          <el-form-item label="演出时间"><el-input v-model="performance.startsAt" type="datetime-local" /></el-form-item>
          <el-button native-type="submit" type="primary">添加场次</el-button>
        </el-form>
      </el-card>

      <el-card v-if="['DRAFT', 'REJECTED'].includes(current.status) && current.performances.length" shadow="never">
        <h3>3. 为场次配置区域票档</h3>
        <el-form :model="tier" label-position="top" class="form-grid tier-grid" @submit.prevent="addTier">
          <el-form-item label="场次">
            <el-select :model-value="tier.performanceId" @update:model-value="choosePerformance">
              <el-option v-for="item in current.performances" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="座位区域">
            <el-select v-model="tier.areaId" placeholder="先选择场次">
              <el-option v-for="area in layout?.areas ?? []" :key="area.id" :label="`${area.name} · ${area.seats.length}座`" :value="area.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="票档名称"><el-input v-model="tier.name" placeholder="A区 680元" /></el-form-item>
          <el-form-item label="价格"><el-input-number v-model="tier.price" :min="0.01" :precision="2" /></el-form-item>
          <el-form-item label="限购"><el-input-number v-model="tier.purchaseLimit" :min="1" :max="6" /></el-form-item>
          <el-button native-type="submit" type="primary">添加票档</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never">
        <h3>配置预览</h3>
        <el-collapse>
          <el-collapse-item v-for="item in current.performances" :key="item.id" :title="`${item.name} · ${new Date(item.startsAt).toLocaleString()}`">
            <el-tag v-for="itemTier in item.ticketTiers" :key="itemTier.id" :color="itemTier.color" effect="dark">
              {{ itemTier.name }} / ¥{{ itemTier.price }} / 限购{{ itemTier.purchaseLimit }}张
            </el-tag>
            <el-empty v-if="!item.ticketTiers.length" description="该场次尚未配置票档" :image-size="48" />
          </el-collapse-item>
        </el-collapse>
        <el-button v-if="['DRAFT', 'REJECTED'].includes(current.status)" type="success" class="submit-review" @click="submitReview">提交审核</el-button>
      </el-card>
    </template>
  </section>
</template>
