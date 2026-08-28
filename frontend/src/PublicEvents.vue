<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import QrcodeVue from 'qrcode.vue'
import { api, errorMessage } from './api'

const props = withDefaults(defineProps<{ authenticated?: boolean }>(), { authenticated: true })
const emit = defineEmits<{ loginRequired: [] }>()

type EventSummary = { id: number; title: string; category: string; status: string }
type Performance = { id: number; name: string; startsAt: string }
type EventDetail = EventSummary & { description: string; posterUrl?: string; purchaseNotice: string; performances: Performance[] }
type Seat = { seatId: number; seatCode: string; price: number; status: string }
type SeatMap = { performanceId: number; performanceName: string; seats: Seat[] }
type Order = { orderNo: string; totalAmount: number; status: string; expiresAt: string; items: { seatCode: string }[] }
type Ticket = { ticketNo: string; code: string; status: string; performanceName: string; startsAt: string; seatCode: string; tierName: string }
type PageResult<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

const labels: Record<string, string> = {
  APPROVED: '已通过', ON_SALE: '售票中', PENDING_PAYMENT: '待支付', PAID: '已支付',
  CANCELLED: '已取消', EXPIRED: '已超时', REFUNDED: '已退款', VALID: '可使用',
  USED: '已核销', REFUNDING: '退款中',
}
const events = ref<EventSummary[]>([])
const detail = ref<EventDetail | null>(null)
const seatMap = ref<SeatMap | null>(null)
const selectedSeats = ref<number[]>([])
const orders = ref<Order[]>([])
const tickets = ref<Ticket[]>([])
const keyword = ref('')
const category = ref('')
const orderStatus = ref('')
const eventPage = reactive({ page: 0, size: 9, total: 0 })
const orderPage = reactive({ page: 0, size: 10, total: 0 })
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)

function label(status: string) { return labels[status] ?? status }
function remaining(expiresAt: string) {
  const seconds = Math.max(0, Math.floor((new Date(expiresAt).getTime() - now.value) / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

async function loadEvents() {
  const { data } = await api.get<PageResult<EventSummary>>('/api/events', {
    params: { keyword: keyword.value, category: category.value || undefined, page: eventPage.page, size: eventPage.size },
  })
  events.value = data.content
  eventPage.total = data.totalElements
}

async function loadOrders() {
  if (!props.authenticated) return
  const { data } = await api.get<PageResult<Order>>('/api/orders', {
    params: { status: orderStatus.value || undefined, page: orderPage.page, size: orderPage.size },
  })
  orders.value = data.content
  orderPage.total = data.totalElements
}

async function load() {
  try {
    await loadEvents()
    if (props.authenticated) {
      const [, ticketResponse] = await Promise.all([
        loadOrders(), api.get<Ticket[]>('/api/tickets'),
      ])
      tickets.value = ticketResponse.data
    }
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function searchEvents() { eventPage.page = 0; loadEvents().catch(error => ElMessage.error(errorMessage(error))) }
function changeEventPage(value: number) { eventPage.page = value - 1; searchEventsPage() }
function searchEventsPage() { loadEvents().catch(error => ElMessage.error(errorMessage(error))) }
function searchOrders() { orderPage.page = 0; loadOrders().catch(error => ElMessage.error(errorMessage(error))) }
function changeOrderPage(value: number) { orderPage.page = value - 1; loadOrders().catch(error => ElMessage.error(errorMessage(error))) }

async function open(eventId: number) {
  try { detail.value = (await api.get<EventDetail>(`/api/events/${eventId}`)).data }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

async function choosePerformance(performanceId: number) {
  try {
    seatMap.value = (await api.get<SeatMap>(`/api/performances/${performanceId}/seats`)).data
    selectedSeats.value = []
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function toggleSeat(seat: Seat) {
  if (seat.status !== 'AVAILABLE') return
  const index = selectedSeats.value.indexOf(seat.seatId)
  if (index >= 0) selectedSeats.value.splice(index, 1)
  else if (selectedSeats.value.length < 6) selectedSeats.value.push(seat.seatId)
}

async function createOrder() {
  if (!props.authenticated) { emit('loginRequired'); return }
  if (!seatMap.value || !selectedSeats.value.length) return
  try {
    const { data } = await api.post<Order>('/api/orders', {
      performanceId: seatMap.value.performanceId, seatIds: selectedSeats.value,
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    orders.value.unshift(data)
    await choosePerformance(seatMap.value.performanceId)
    ElMessage.success('锁座成功，请在10分钟内完成支付')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function pay(order: Order) {
  try {
    const payment = (await api.post<{ paymentNo: string }>('/api/payments', { orderNo: order.orderNo })).data
    await api.post(`/api/payments/${payment.paymentNo}/simulate-success`)
    await load()
    ElMessage.success('模拟支付成功，电子票已生成')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function cancel(order: Order) {
  try { await api.post(`/api/orders/${order.orderNo}/cancel`); await load(); ElMessage.success('订单已取消') }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

async function refund(order: Order) {
  try { await api.post(`/api/orders/${order.orderNo}/refunds`); await load(); ElMessage.success('模拟退款成功') }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

onMounted(load)
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <el-tabs type="border-card">
    <el-tab-pane label="活动购票">
      <section class="admin-panel">
        <div class="panel-heading">
          <div><span class="eyebrow">CITY EVENTS</span><h2>已上线活动</h2></div>
          <div class="search-row">
            <el-input v-model="keyword" clearable placeholder="搜索活动名称" class="event-search"
                      @keyup.enter="searchEvents" @clear="searchEvents" />
            <el-select v-model="category" clearable placeholder="全部类别" @change="searchEvents">
              <el-option label="演唱会" value="CONCERT" /><el-option label="话剧" value="THEATRE" />
              <el-option label="展览" value="EXHIBITION" /><el-option label="喜剧" value="COMEDY" />
              <el-option label="校园活动" value="CAMPUS" /><el-option label="其他" value="OTHER" />
            </el-select>
            <el-button type="primary" @click="searchEvents">搜索</el-button>
          </div>
        </div>
        <el-row :gutter="18">
          <el-col v-for="item in events" :key="item.id" :xs="24" :sm="12" :md="8">
            <el-card shadow="hover" class="event-card" @click="open(item.id)">
              <span class="eyebrow">{{ item.category }}</span><h3>{{ item.title }}</h3><el-tag type="success">{{ label(item.status) }}</el-tag>
            </el-card>
          </el-col>
        </el-row>
        <el-empty v-if="!events.length" description="没有匹配的已上线活动" />
        <el-pagination v-if="eventPage.total > eventPage.size" background layout="prev, pager, next"
                       :current-page="eventPage.page + 1" :page-size="eventPage.size" :total="eventPage.total"
                       @current-change="changeEventPage" />
      </section>
    </el-tab-pane>
    <el-tab-pane v-if="authenticated" label="我的订单">
      <div class="search-row order-filter">
        <el-select v-model="orderStatus" clearable placeholder="全部状态" @change="searchOrders">
          <el-option label="待支付" value="PENDING_PAYMENT" /><el-option label="已支付" value="PAID" />
          <el-option label="已取消" value="CANCELLED" /><el-option label="已超时" value="EXPIRED" />
          <el-option label="已退款" value="REFUNDED" />
        </el-select>
      </div>
      <el-table :data="orders" empty-text="还没有订单">
        <el-table-column prop="orderNo" label="订单号" /><el-table-column prop="totalAmount" label="金额" width="100" />
        <el-table-column label="状态" width="170"><template #default="scope">
          {{ label(scope.row.status) }}<span v-if="scope.row.status === 'PENDING_PAYMENT'"> · {{ remaining(scope.row.expiresAt) }}</span>
        </template></el-table-column>
        <el-table-column label="座位"><template #default="scope">{{ scope.row.items.map((item: { seatCode: string }) => item.seatCode).join('、') }}</template></el-table-column>
        <el-table-column label="操作" width="220"><template #default="scope">
          <el-button v-if="scope.row.status === 'PENDING_PAYMENT'" text type="primary" @click="pay(scope.row)">模拟支付</el-button>
          <el-button v-if="scope.row.status === 'PENDING_PAYMENT'" text @click="cancel(scope.row)">取消</el-button>
          <el-button v-if="scope.row.status === 'PAID'" text type="warning" @click="refund(scope.row)">退款</el-button>
        </template></el-table-column>
      </el-table>
      <el-pagination v-if="orderPage.total > orderPage.size" background layout="prev, pager, next"
                     :current-page="orderPage.page + 1" :page-size="orderPage.size" :total="orderPage.total"
                     @current-change="changeOrderPage" />
    </el-tab-pane>
    <el-tab-pane v-if="authenticated" label="我的电子票">
      <el-card v-for="ticket in tickets" :key="ticket.ticketNo" shadow="never" class="ticket-card">
        <div class="ticket-content">
          <div><strong>{{ ticket.performanceName }} · {{ ticket.seatCode }}</strong> <el-tag>{{ label(ticket.status) }}</el-tag>
            <p>{{ new Date(ticket.startsAt).toLocaleString() }} · {{ ticket.tierName }}</p><code>{{ ticket.code }}</code>
          </div>
          <qrcode-vue :value="ticket.code" :size="132" level="M" render-as="svg" />
        </div>
      </el-card>
      <el-empty v-if="!tickets.length" description="支付成功后将在这里生成电子票" />
    </el-tab-pane>
  </el-tabs>

  <el-drawer :model-value="detail !== null" :title="detail?.title" size="620px" @close="detail = null; seatMap = null">
    <template v-if="detail">
      <img v-if="detail.posterUrl" :src="detail.posterUrl" :alt="detail.title" class="event-poster" />
      <p>{{ detail.description }}</p><el-alert :title="detail.purchaseNotice" type="info" :closable="false" /><h3>选择场次</h3>
      <el-card v-for="item in detail.performances" :key="item.id" shadow="never" class="review-performance">
        <strong>{{ item.name }}</strong><p>{{ new Date(item.startsAt).toLocaleString() }}</p>
        <el-button type="primary" plain @click="choosePerformance(item.id)">选择座位</el-button>
      </el-card>
      <template v-if="seatMap">
        <h3>{{ seatMap.performanceName }} · 座位图</h3>
        <div class="seat-grid">
          <button v-for="seat in seatMap.seats" :key="seat.seatId" :disabled="seat.status !== 'AVAILABLE'"
                  :class="{ selected: selectedSeats.includes(seat.seatId) }" @click="toggleSeat(seat)">
            {{ seat.seatCode }}<small>¥{{ seat.price }}</small>
          </button>
        </div>
        <el-button type="success" :disabled="!selectedSeats.length" @click="createOrder">
          {{ authenticated ? `锁定 ${selectedSeats.length} 个座位并创建订单` : '登录后下单' }}
        </el-button>
      </template>
    </template>
  </el-drawer>
</template>
