import axios from 'axios'

export const api = axios.create()

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('starticket_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export function errorMessage(error: unknown) {
  return axios.isAxiosError(error) ? error.response?.data?.detail ?? '请求失败，请稍后重试' : '请求失败，请稍后重试'
}
