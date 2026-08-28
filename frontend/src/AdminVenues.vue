<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from './api'

type Venue = { id: number; name: string; city: string; address: string; enabled: boolean }
type Seat = { id: number; rowLabel: string; seatNumber: number; code: string; enabled: boolean }
type Area = { id: number; name: string; code: string; sortOrder: number; seats: Seat[] }
type Layout = { venue: Venue; areas: Area[] }

const venues = ref<Venue[]>([])
const layout = ref<Layout | null>(null)
const loading = ref(false)
const venueForm = reactive({ name: '', city: '', address: '' })
const areaForm = reactive({ name: '', code: '', sortOrder: 0 })
const generation = reactive({ rowCount: 10, seatsPerRow: 20 })

async function loadVenues() {
  const { data } = await api.get<Venue[]>('/api/admin/venues')
  venues.value = data
}

async function createVenue() {
  try {
    loading.value = true
    const { data } = await api.post<Venue>('/api/admin/venues', venueForm)
    venues.value.push(data)
    Object.assign(venueForm, { name: '', city: '', address: '' })
    ElMessage.success('场馆已创建')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function openLayout(venueId: number) {
  const { data } = await api.get<Layout>(`/api/admin/venues/${venueId}/layout`)
  layout.value = data
}

async function createArea() {
  if (!layout.value) return
  try {
    await api.post(`/api/admin/venues/${layout.value.venue.id}/areas`, areaForm)
    Object.assign(areaForm, { name: '', code: '', sortOrder: 0 })
    await openLayout(layout.value.venue.id)
    ElMessage.success('区域已创建')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function generateSeats(areaId: number) {
  if (!layout.value) return
  try {
    const { data } = await api.post<{ created: number }>(`/api/admin/areas/${areaId}/seats/generate`, generation)
    await openLayout(layout.value.venue.id)
    ElMessage.success(`已生成 ${data.created} 个座位`)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(async () => {
  try {
    await loadVenues()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
})
</script>

<template>
  <section class="admin-panel">
    <div class="panel-heading">
      <div>
        <span class="eyebrow">VENUE ADMIN</span>
        <h2>场馆与座位模板</h2>
      </div>
      <span>{{ venues.length }} 个场馆</span>
    </div>

    <el-card shadow="never">
      <h3>新建场馆</h3>
      <el-form :model="venueForm" label-position="top" class="form-grid" @submit.prevent="createVenue">
        <el-form-item label="场馆名称"><el-input v-model="venueForm.name" /></el-form-item>
        <el-form-item label="城市"><el-input v-model="venueForm.city" /></el-form-item>
        <el-form-item label="详细地址"><el-input v-model="venueForm.address" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="loading">创建场馆</el-button>
      </el-form>
    </el-card>

    <el-table :data="venues" empty-text="还没有场馆">
      <el-table-column prop="name" label="场馆" />
      <el-table-column prop="city" label="城市" width="120" />
      <el-table-column prop="address" label="地址" />
      <el-table-column label="操作" width="120">
        <template #default="scope">
          <el-button text type="primary" @click="openLayout(scope.row.id)">座位布局</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-card v-if="layout" shadow="never">
      <h3>{{ layout.venue.name }} · 区域</h3>
      <el-form :model="areaForm" inline @submit.prevent="createArea">
        <el-form-item label="区域名称"><el-input v-model="areaForm.name" placeholder="一层A区" /></el-form-item>
        <el-form-item label="编码"><el-input v-model="areaForm.code" placeholder="A1" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="areaForm.sortOrder" :min="0" /></el-form-item>
        <el-button native-type="submit" type="primary">添加区域</el-button>
      </el-form>

      <div class="generation-options">
        <span>生成规格</span>
        <el-input-number v-model="generation.rowCount" :min="1" :max="100" /> 排
        <el-input-number v-model="generation.seatsPerRow" :min="1" :max="200" /> 座/排
      </div>

      <el-table :data="layout.areas" empty-text="还没有区域">
        <el-table-column prop="name" label="区域" />
        <el-table-column prop="code" label="编码" width="100" />
        <el-table-column label="座位数" width="100">
          <template #default="scope">{{ scope.row.seats.length }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="scope">
            <el-button
              v-if="scope.row.seats.length === 0"
              text
              type="primary"
              @click="generateSeats(scope.row.id)"
            >生成座位</el-button>
            <span v-else>已生成</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>
