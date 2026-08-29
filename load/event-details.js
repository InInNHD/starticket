import http from 'k6/http'
import { check } from 'k6'

export const options = {
  stages: [
    { duration: '20s', target: 100 },
    { duration: '40s', target: 500 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
}

const baseUrl = __ENV.BASE_URL || 'http://localhost:18080'
const eventId = __ENV.EVENT_ID

export default function () {
  const response = http.get(`${baseUrl}/api/events/${eventId}`)
  check(response, { '活动详情成功': result => result.status === 200 })
}
