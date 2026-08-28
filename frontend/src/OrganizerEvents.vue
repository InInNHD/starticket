<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage } from './api'

type Venue = { id: number; name: string; city: string }
type Area = { id: number; name: string; seats: unknown[] }
type Layout = { venue: Venue; areas: Area[] }
type Tier = { id: number; name: string; price: number; color: string; purchaseLimit: number; enabled: boolean }
type Performance = {
  id: number; venueId: number; name: string; startsAt: string; salesStartAt: string; salesEndAt: string
  status: string; ticketTiers: Tier[]
}
type EventSummary = { id: number; title: string; category: string; status: string; reviewNote?: string }
type EventDetail = EventSummary & { description: string; purchaseNotice: string; performances: Performance[] }
type PageResult<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
type SalesBreakdown = {
  performanceId: number; performanceName: string; tierId: number; tierName: string
  capacity: number; soldTickets: number; refundedTickets: number; netRevenue: number
}
type SalesSummary = {
  eventId: number; eventTitle: string; totalOrders: number; pendingOrders: number; paidOrders: number
  refundedOrders: number; soldTickets: number; refundedTickets: number; grossRevenue: number
  refundAmount: number; netRevenue: number; breakdown: SalesBreakdown[]
}
type OrderSummary = {
  orderNo: string; username: string; eventTitle: string; performanceName: string; totalAmount: number
  status: string; itemCount: number; createdAt: string
}

const categoryOptions = [
  ['CONCERT', '演唱会'], ['THEATRE', '话剧'], ['EXHIBITION', '展览'],
  ['COMEDY', '喜剧'], ['CAMPUS', '校园活动'], ['OTHER', '其他'],
]
const statusOptions = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'ON_SALE', 'REJECTED', 'CANCELLED', 'OFF_SHELF', 'ENDED']
const orderStatusOptions = ['PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED', 'REFUNDING', 'REFUNDED']
const events = ref<EventSummary[]>([])
const venues = ref<Venue[]>([])
const current = ref<EventDetail | null>(null)
const layout = ref<Layout | null>(null)
const sales = ref<SalesSummary | null>(null)
const eventOrders = ref<OrderSummary[]>([])
const loading = ref(false)
const eventQuery = reactive({ keyword: '', status: '', page: 0, size: 10, total: 0 })
const orderQuery = reactive({ keyword: '', status: '', page: 0, size: 10, total: 0 })
const draft = reactive({ title: '', category: 'CONCERT', description: '', posterUrl: '', purchaseNotice: '' })
const performance = reactive({ id: undefined as number | undefined, venueId: undefined as number | undefined, name: '', startsAt: '', salesStartAt: '', salesEndAt: '' })
const tier = reactive({ id: undefined as number | undefined, performanceId: undefined as number | undefined, areaId: undefined as number | undefined, name: '', price: 100, color: '#6B3BFF', purchaseLimit: 4, enabled: true })

function formatMoney(value: number) { return `¥${Number(value).toFixed(2)}` }
function sellThrough(row: SalesBreakdown) { return row.capacity ? `${(row.soldTickets / row.capacity * 100).toFixed(1)}%` : '0%' }
function localDateTime(value: string) {
  const date = new Date(value)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

async function loadEvents() {
  const { data } = await api.get<PageResult<EventSummary>>('/api/organizer/events', {
    params: { keyword: eventQuery.keyword, status: eventQuery.status || undefined, page: eventQuery.page, size: eventQuery.size },
  })
  events.value = data.content
  eventQuery.total = data.totalElements
}

function searchEvents() { eventQuery.page = 0; loadEvents().catch(error => ElMessage.error(errorMessage(error))) }
function changeEventPage(value: number) { eventQuery.page = value - 1; loadEvents().catch(error => ElMessage.error(errorMessage(error))) }

async function openEvent(eventId: number) {
  try {
    const { data } = await api.get<EventDetail>(`/api/organizer/events/${eventId}`)
    current.value = data
    await Promise.all([loadSales(), loadEventOrders()])
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function loadSales() {
  if (!current.value) return
  sales.value = (await api.get<SalesSummary>(`/api/organizer/events/${current.value.id}/sales-summary`)).data
}

async function loadEventOrders() {
  if (!current.value) return
  const { data } = await api.get<PageResult<OrderSummary>>(`/api/organizer/events/${current.value.id}/orders`, {
    params: { keyword: orderQuery.keyword, status: orderQuery.status || undefined, page: orderQuery.page, size: orderQuery.size },
  })
  eventOrders.value = data.content
  orderQuery.total = data.totalElements
}

function searchOrders() { orderQuery.page = 0; loadEventOrders().catch(error => ElMessage.error(errorMessage(error))) }
function changeOrderPage(value: number) { orderQuery.page = value - 1; loadEventOrders().catch(error => ElMessage.error(errorMessage(error))) }

async function createEvent() {
  try {
    loading.value = true
    const { data } = await api.post<EventDetail>('/api/organizer/events', draft)
    Object.assign(draft, { title: '', category: 'CONCERT', description: '', posterUrl: '', purchaseNotice: '' })
    current.value = data
    await Promise.all([loadEvents(), loadSales(), loadEventOrders()])
    ElMessage.success('活动草稿已创建')
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}

async function selectVenue(venueId: number) {
  performance.venueId = venueId
  tier.areaId = undefined
  await loadLayout(venueId)
}

async function loadLayout(venueId: number) {
  layout.value = (await api.get<Layout>(`/api/organizer/venues/${venueId}/layout`)).data
}

function resetPerformance() {
  Object.assign(performance, { id: undefined, venueId: undefined, name: '', startsAt: '', salesStartAt: '', salesEndAt: '' })
  layout.value = null
}

function editPerformance(item: Performance) {
  Object.assign(performance, {
    id: item.id, venueId: item.venueId, name: item.name, startsAt: localDateTime(item.startsAt),
    salesStartAt: localDateTime(item.salesStartAt), salesEndAt: localDateTime(item.salesEndAt),
  })
  loadLayout(item.venueId).catch(error => ElMessage.error(errorMessage(error)))
}

async function savePerformance() {
  if (!current.value || !performance.venueId) return
  const body = {
    venueId: performance.venueId, name: performance.name,
    startsAt: new Date(performance.startsAt).toISOString(),
    salesStartAt: new Date(performance.salesStartAt).toISOString(),
    salesEndAt: new Date(performance.salesEndAt).toISOString(),
  }
  try {
    if (performance.id) await api.put(`/api/organizer/performances/${performance.id}`, body)
    else await api.post(`/api/organizer/events/${current.value.id}/performances`, body)
    const message = performance.id ? '场次已更新' : '场次已添加'
    resetPerformance()
    await openEvent(current.value.id)
    ElMessage.success(message)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function cancelPerformance(item: Performance) {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(`确定停用场次“${item.name}”吗？`, '停用场次', { type: 'warning' })
    await api.post(`/api/organizer/performances/${item.id}/cancel`)
    await openEvent(current.value.id)
    ElMessage.success('场次已停用')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function choosePerformance(performanceId: number) {
  tier.performanceId = performanceId
  tier.areaId = undefined
  const selected = current.value?.performances.find(item => item.id === performanceId)
  if (selected) await loadLayout(selected.venueId)
}

function resetTier() {
  Object.assign(tier, { id: undefined, performanceId: undefined, areaId: undefined, name: '', price: 100, color: '#6B3BFF', purchaseLimit: 4, enabled: true })
}

async function editTier(performanceId: number, item: Tier) {
  await choosePerformance(performanceId)
  Object.assign(tier, { id: item.id, performanceId, name: item.name, price: item.price, color: item.color, purchaseLimit: item.purchaseLimit, enabled: item.enabled })
}

async function saveTier() {
  if (!current.value || !tier.performanceId) return
  try {
    if (tier.id) {
      await api.put(`/api/organizer/tiers/${tier.id}`, {
        name: tier.name, price: tier.price, color: tier.color, purchaseLimit: tier.purchaseLimit, enabled: tier.enabled,
      })
    } else {
      await api.post(`/api/organizer/performances/${tier.performanceId}/tiers`, {
        areaId: tier.areaId, name: tier.name, price: tier.price, color: tier.color, purchaseLimit: tier.purchaseLimit,
      })
    }
    const message = tier.id ? '票档已更新' : '票档已添加'
    resetTier()
    await openEvent(current.value.id)
    ElMessage.success(message)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function toggleTier(item: Tier, enabled: boolean) {
  if (!current.value) return
  try {
    await api.put(`/api/organizer/tiers/${item.id}`, {
      name: item.name, price: item.price, color: item.color, purchaseLimit: item.purchaseLimit, enabled,
    })
    await openEvent(current.value.id)
    ElMessage.success(enabled ? '票档已启用' : '票档已停用')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function submitReview() {
  if (!current.value) return
  try {
    current.value = (await api.post<EventDetail>(`/api/organizer/events/${current.value.id}/submit`)).data
    await loadEvents()
    ElMessage.success('已提交管理员审核')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function cancelEvent() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(`确定取消活动“${current.value.title}”吗？取消后不能恢复。`, '取消活动', { type: 'warning' })
    current.value = (await api.post<EventDetail>(`/api/organizer/events/${current.value.id}/cancel`)).data
    await loadEvents()
    ElMessage.success('活动已取消')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

onMounted(async () => {
  try {
    const [, venueResponse] = await Promise.all([loadEvents(), api.get<Venue[]>('/api/organizer/venues')])
    venues.value = venueResponse.data
  } catch (error) { ElMessage.error(errorMessage(error)) }
})
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading">
      <div><span class="eyebrow">ORGANIZER</span><h2>活动运营工作台</h2></div>
      <span>共 {{ eventQuery.total }} 个活动</span>
    </div>

    <el-card shadow="never">
      <h3>创建活动草稿</h3>
      <el-form :model="draft" label-position="top" @submit.prevent="createEvent">
        <div class="form-grid event-draft-grid">
          <el-form-item label="活动名称"><el-input v-model="draft.title" /></el-form-item>
          <el-form-item label="类别"><el-select v-model="draft.category">
            <el-option v-for="item in categoryOptions" :key="item[0]" :label="item[1]" :value="item[0]" />
          </el-select></el-form-item>
          <el-form-item label="海报地址（可选）"><el-input v-model="draft.posterUrl" /></el-form-item>
        </div>
        <el-form-item label="活动介绍"><el-input v-model="draft.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="购票须知"><el-input v-model="draft.purchaseNotice" type="textarea" :rows="2" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="loading">保存草稿</el-button>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="search-row">
        <el-input v-model="eventQuery.keyword" clearable placeholder="搜索活动名称" @keyup.enter="searchEvents" @clear="searchEvents" />
        <el-select v-model="eventQuery.status" clearable placeholder="全部状态" @change="searchEvents">
          <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-button type="primary" @click="searchEvents">查询</el-button>
      </div>
      <el-table :data="events" empty-text="还没有活动">
        <el-table-column prop="title" label="活动" /><el-table-column prop="category" label="类别" width="130" />
        <el-table-column prop="status" label="状态" width="160" /><el-table-column prop="reviewNote" label="审核意见" />
        <el-table-column label="操作" width="110"><template #default="scope">
          <el-button text type="primary" @click="openEvent(scope.row.id)">配置/运营</el-button>
        </template></el-table-column>
      </el-table>
      <el-pagination v-if="eventQuery.total > eventQuery.size" background layout="prev, pager, next"
                     :current-page="eventQuery.page + 1" :page-size="eventQuery.size" :total="eventQuery.total"
                     @current-change="changeEventPage" />
    </el-card>

    <template v-if="current">
      <el-alert :title="`当前活动：${current.title}（${current.status}）`" type="info" :closable="false" />
      <div v-if="['DRAFT', 'REJECTED', 'PENDING_REVIEW', 'APPROVED'].includes(current.status)" class="lifecycle-actions">
        <el-button type="danger" plain @click="cancelEvent">取消活动</el-button>
      </div>

      <el-card v-if="['DRAFT', 'REJECTED'].includes(current.status)" shadow="never">
        <h3>{{ performance.id ? '编辑场次' : '添加演出场次' }}</h3>
        <el-form :model="performance" label-position="top" class="form-grid performance-grid" @submit.prevent="savePerformance">
          <el-form-item label="场馆"><el-select :model-value="performance.venueId" @update:model-value="selectVenue" placeholder="选择场馆">
            <el-option v-for="venue in venues" :key="venue.id" :label="`${venue.name} · ${venue.city}`" :value="venue.id" />
          </el-select></el-form-item>
          <el-form-item label="场次名称"><el-input v-model="performance.name" /></el-form-item>
          <el-form-item label="开售时间"><el-input v-model="performance.salesStartAt" type="datetime-local" /></el-form-item>
          <el-form-item label="停售时间"><el-input v-model="performance.salesEndAt" type="datetime-local" /></el-form-item>
          <el-form-item label="演出时间"><el-input v-model="performance.startsAt" type="datetime-local" /></el-form-item>
          <div><el-button native-type="submit" type="primary">{{ performance.id ? '保存修改' : '添加场次' }}</el-button>
            <el-button v-if="performance.id" @click="resetPerformance">取消编辑</el-button></div>
        </el-form>
      </el-card>

      <el-card v-if="['DRAFT', 'REJECTED'].includes(current.status) && current.performances.some(item => item.status === 'SCHEDULED')" shadow="never">
        <h3>{{ tier.id ? '编辑票档' : '配置区域票档' }}</h3>
        <el-form :model="tier" label-position="top" class="form-grid tier-grid" @submit.prevent="saveTier">
          <el-form-item label="场次"><el-select :model-value="tier.performanceId" :disabled="Boolean(tier.id)" @update:model-value="choosePerformance">
            <el-option v-for="item in current.performances.filter(row => row.status === 'SCHEDULED')" :key="item.id" :label="item.name" :value="item.id" />
          </el-select></el-form-item>
          <el-form-item label="座位区域"><el-select v-model="tier.areaId" :disabled="Boolean(tier.id)" placeholder="先选择场次">
            <el-option v-for="area in layout?.areas ?? []" :key="area.id" :label="`${area.name} · ${area.seats.length}座`" :value="area.id" />
          </el-select></el-form-item>
          <el-form-item label="票档名称"><el-input v-model="tier.name" /></el-form-item>
          <el-form-item label="价格"><el-input-number v-model="tier.price" :min="0.01" :precision="2" /></el-form-item>
          <el-form-item label="限购"><el-input-number v-model="tier.purchaseLimit" :min="1" :max="6" /></el-form-item>
          <div><el-button native-type="submit" type="primary">{{ tier.id ? '保存修改' : '添加票档' }}</el-button>
            <el-button v-if="tier.id" @click="resetTier">取消编辑</el-button></div>
        </el-form>
      </el-card>

      <el-card shadow="never">
        <h3>配置预览</h3>
        <el-collapse>
          <el-collapse-item v-for="item in current.performances" :key="item.id"
                            :title="`${item.name} · ${item.status} · ${new Date(item.startsAt).toLocaleString()}`">
            <div v-if="['DRAFT', 'REJECTED'].includes(current.status) && item.status === 'SCHEDULED'" class="config-actions">
              <el-button text type="primary" @click="editPerformance(item)">编辑场次</el-button>
              <el-button text type="danger" @click="cancelPerformance(item)">停用场次</el-button>
            </div>
            <div v-for="itemTier in item.ticketTiers" :key="itemTier.id" class="tier-row">
              <el-tag :color="itemTier.color" effect="dark">{{ itemTier.name }} / ¥{{ itemTier.price }} / 限购{{ itemTier.purchaseLimit }}张</el-tag>
              <template v-if="['DRAFT', 'REJECTED'].includes(current.status)">
                <el-switch :model-value="itemTier.enabled" active-text="启用" inactive-text="停用"
                           @change="toggleTier(itemTier, Boolean($event))" />
                <el-button text type="primary" @click="editTier(item.id, itemTier)">编辑</el-button>
              </template>
            </div>
            <el-empty v-if="!item.ticketTiers.length" description="该场次尚未配置票档" :image-size="48" />
          </el-collapse-item>
        </el-collapse>
        <el-button v-if="['DRAFT', 'REJECTED'].includes(current.status)" type="success" class="submit-review" @click="submitReview">提交审核</el-button>
      </el-card>

      <el-card v-if="sales" shadow="never">
        <div class="panel-heading"><div><span class="eyebrow">SALES</span><h3>销售看板</h3></div></div>
        <div class="stat-grid">
          <div><span>订单总数</span><strong>{{ sales.totalOrders }}</strong></div>
          <div><span>有效售票</span><strong>{{ sales.soldTickets }}</strong></div>
          <div><span>净收入</span><strong>{{ formatMoney(sales.netRevenue) }}</strong></div>
          <div><span>退款</span><strong>{{ sales.refundedOrders }} 单 / {{ formatMoney(sales.refundAmount) }}</strong></div>
        </div>
        <el-table :data="sales.breakdown" empty-text="尚无票档">
          <el-table-column prop="performanceName" label="场次" /><el-table-column prop="tierName" label="票档" />
          <el-table-column prop="capacity" label="容量" width="90" /><el-table-column prop="soldTickets" label="有效售出" width="100" />
          <el-table-column label="售出率" width="100"><template #default="scope">{{ sellThrough(scope.row) }}</template></el-table-column>
          <el-table-column prop="refundedTickets" label="退款票" width="90" />
          <el-table-column label="净收入" width="120"><template #default="scope">{{ formatMoney(scope.row.netRevenue) }}</template></el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <div class="panel-heading"><h3>活动订单</h3><span>{{ orderQuery.total }} 单</span></div>
        <div class="search-row">
          <el-input v-model="orderQuery.keyword" clearable placeholder="订单号、用户名或活动" @keyup.enter="searchOrders" @clear="searchOrders" />
          <el-select v-model="orderQuery.status" clearable placeholder="全部状态" @change="searchOrders">
            <el-option v-for="item in orderStatusOptions" :key="item" :label="item" :value="item" />
          </el-select><el-button type="primary" @click="searchOrders">查询</el-button>
        </div>
        <el-table :data="eventOrders" empty-text="暂无订单">
          <el-table-column prop="orderNo" label="订单号" min-width="210" /><el-table-column prop="username" label="用户" width="120" />
          <el-table-column prop="performanceName" label="场次" /><el-table-column prop="itemCount" label="票数" width="75" />
          <el-table-column prop="totalAmount" label="金额" width="100" /><el-table-column prop="status" label="状态" width="150" />
        </el-table>
        <el-pagination v-if="orderQuery.total > orderQuery.size" background layout="prev, pager, next"
                       :current-page="orderQuery.page + 1" :page-size="orderQuery.size" :total="orderQuery.total"
                       @current-change="changeOrderPage" />
      </el-card>
    </template>
  </section>
</template>
