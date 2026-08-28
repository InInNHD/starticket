<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from './api'

type OrderSummary = {
  orderNo: string; username: string; eventTitle: string; performanceName: string
  totalAmount: number; status: string; itemCount: number; createdAt: string
}
type PageResult<T> = { content: T[]; totalElements: number }

const statuses = ['PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED', 'REFUNDING', 'REFUNDED']
const orders = ref<OrderSummary[]>([])
const query = reactive({ keyword: '', status: '', page: 0, size: 20, total: 0 })

async function load() {
  try {
    const { data } = await api.get<PageResult<OrderSummary>>('/api/admin/orders', {
      params: { keyword: query.keyword, status: query.status || undefined, page: query.page, size: query.size },
    })
    orders.value = data.content
    query.total = data.totalElements
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function search() { query.page = 0; load() }
function changePage(value: number) { query.page = value - 1; load() }

onMounted(load)
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading"><div><span class="eyebrow">ORDERS</span><h2>全平台订单</h2></div><span>{{ query.total }} 单</span></div>
    <div class="search-row">
      <el-input v-model="query.keyword" clearable placeholder="订单号、用户名或活动名称"
                @keyup.enter="search" @clear="search" />
      <el-select v-model="query.status" clearable placeholder="全部状态" @change="search">
        <el-option v-for="item in statuses" :key="item" :label="item" :value="item" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </div>
    <el-table :data="orders" empty-text="暂无订单">
      <el-table-column prop="orderNo" label="订单号" min-width="210" />
      <el-table-column prop="username" label="用户" width="120" />
      <el-table-column prop="eventTitle" label="活动" />
      <el-table-column prop="performanceName" label="场次" />
      <el-table-column prop="itemCount" label="票数" width="75" />
      <el-table-column prop="totalAmount" label="金额" width="100" />
      <el-table-column prop="status" label="状态" width="150" />
      <el-table-column label="创建时间" width="180"><template #default="scope">
        {{ new Date(scope.row.createdAt).toLocaleString() }}
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="query.total > query.size" background layout="prev, pager, next"
                   :current-page="query.page + 1" :page-size="query.size" :total="query.total"
                   @current-change="changePage" />
  </section>
</template>
