<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PublicEvents from './PublicEvents.vue'
import { api, errorMessage } from './api'

const AdminEvents = defineAsyncComponent(() => import('./AdminEvents.vue'))
const AdminVenues = defineAsyncComponent(() => import('./AdminVenues.vue'))
const OrganizerEvents = defineAsyncComponent(() => import('./OrganizerEvents.vue'))
const CheckerPanel = defineAsyncComponent(() => import('./CheckerPanel.vue'))
const AdminOperations = defineAsyncComponent(() => import('./AdminOperations.vue'))
const AdminOrders = defineAsyncComponent(() => import('./AdminOrders.vue'))

type User = {
  id: number
  username: string
  email: string
  roles: string[]
}

type AuthResponse = {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const user = ref<User | null>(null)
const workspace = ref('user')
const form = reactive({ username: '', email: '', login: 'admin', password: 'Password123' })
const title = computed(() => (mode.value === 'login' ? '登录 StarTicket' : '创建账户'))

async function submit() {
  if (mode.value === 'login' && (!form.login.trim() || !form.password)) {
    ElMessage.warning('请输入用户名或邮箱和密码')
    return
  }
  if (mode.value === 'register') {
    if (!/^[a-zA-Z0-9_]{4,32}$/.test(form.username)) {
      ElMessage.warning('用户名需为4–32位字母、数字或下划线')
      return
    }
    if (!/^\S+@\S+\.\S+$/.test(form.email) || form.password.length < 8 || form.password.length > 72) {
      ElMessage.warning('请输入有效邮箱，密码长度需为8–72位')
      return
    }
  }
  loading.value = true
  try {
    const path = mode.value === 'login' ? '/api/auth/login' : '/api/auth/register'
    const body = mode.value === 'login'
      ? { login: form.login, password: form.password }
      : { username: form.username, email: form.email, password: form.password }
    const { data } = await api.post<AuthResponse>(path, body)
    localStorage.setItem('starticket_token', data.accessToken)
    user.value = data.user
    selectWorkspace(data.user)
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function logout() {
  localStorage.removeItem('starticket_token')
  user.value = null
}

function selectWorkspace(current: User) {
  workspace.value = current.roles.includes('ADMIN') ? 'admin'
    : current.roles.includes('ORGANIZER') ? 'organizer'
      : current.roles.includes('CHECKER') ? 'checker' : 'user'
}

function requestLogin() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
  ElMessage.info('请先登录或注册后再下单')
}

onMounted(async () => {
  if (!localStorage.getItem('starticket_token')) return
  try {
    const { data } = await api.get<User>('/api/me')
    user.value = data
    selectWorkspace(data)
  } catch {
    localStorage.removeItem('starticket_token')
  }
})
</script>

<template>
  <template v-if="!user">
    <div class="demo-banner">公开演示沙箱 · 管理员账号已预填 · 请勿录入真实个人信息</div>
    <main class="shell">
      <section class="intro">
        <span class="eyebrow">STAR TICKET</span>
        <h1>城市现场，<br />一票抵达。</h1>
        <p>发现演唱会、话剧、展览和校园活动，选座、购票、入场核销一站完成。</p>
      </section>

      <el-card class="auth-card" shadow="never">
        <h2>{{ title }}</h2>
        <el-segmented v-model="mode" :options="[
          { label: '登录', value: 'login' },
          { label: '注册', value: 'register' },
        ]" />
        <el-form label-position="top" @submit.prevent="submit">
          <template v-if="mode === 'register'">
            <el-form-item label="用户名">
              <el-input v-model="form.username" autocomplete="username" placeholder="4–32位字母、数字或下划线" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="form.email" autocomplete="email" placeholder="name@example.com" />
            </el-form-item>
          </template>
          <el-form-item v-else label="用户名或邮箱">
            <el-input v-model="form.login" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" show-password
                      :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" />
          </el-form-item>
          <el-button native-type="submit" type="primary" size="large" :loading="loading">
            {{ mode === 'login' ? '登录' : '注册并登录' }}
          </el-button>
        </el-form>
      </el-card>
    </main>
    <section class="guest-events"><PublicEvents :authenticated="false" @login-required="requestLogin" /></section>
  </template>

  <main v-else class="dashboard-shell">
    <div class="demo-banner dashboard-banner">公开演示沙箱 · 数据仅用于项目展示</div>
    <header class="dashboard-header">
      <div><strong>StarTicket</strong><span>{{ user.username }} · {{ user.roles.join(' / ') }}</span></div>
      <el-button @click="logout">退出登录</el-button>
    </header>
    <el-tabs v-model="workspace" type="border-card">
      <el-tab-pane label="活动购票" name="user"><PublicEvents /></el-tab-pane>
      <el-tab-pane v-if="user.roles.includes('ORGANIZER') || user.roles.includes('ADMIN')"
                   label="主办方工作台" name="organizer"><OrganizerEvents /></el-tab-pane>
      <el-tab-pane v-if="user.roles.includes('CHECKER') || user.roles.includes('ADMIN')"
                   label="检票工作台" name="checker"><CheckerPanel /></el-tab-pane>
      <el-tab-pane v-if="user.roles.includes('ADMIN')" label="管理工作台" name="admin">
        <el-tabs type="card">
          <el-tab-pane label="场馆资源"><AdminVenues /></el-tab-pane>
          <el-tab-pane label="活动审核"><AdminEvents /></el-tab-pane>
          <el-tab-pane label="平台订单"><AdminOrders /></el-tab-pane>
          <el-tab-pane label="消息异常"><AdminOperations /></el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>
  </main>
</template>
