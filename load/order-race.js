import http from 'k6/http'
import { check } from 'k6'
import { Counter, Rate } from 'k6/metrics'

http.setResponseCallback(http.expectedStatuses(201, 409, 429))

const ordersCreated = new Counter('orders_created')
const seatConflicts = new Counter('seat_conflicts')
const technicalErrorRate = new Rate('technical_error_rate')

export const options = {
  vus: Number(__ENV.VUS || 100),
  iterations: Number(__ENV.ITERATIONS || 100),
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  thresholds: {
    http_req_duration: ['p(95)<1000'],
    technical_error_rate: ['rate==0'],
  },
}

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080'
const seatIds = (__ENV.SEAT_IDS || '').split(',').filter(Boolean).map(Number)

export default function () {
  const seatId = seatIds[__ITER % seatIds.length]
  const response = http.post(`${baseUrl}/api/orders`, JSON.stringify({
    performanceId: Number(__ENV.PERFORMANCE_ID), seatIds: [seatId],
  }), { headers: {
    Authorization: `Bearer ${__ENV.TOKEN}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': `k6-${__VU}-${__ITER}`,
  } })
  ordersCreated.add(response.status === 201)
  seatConflicts.add(response.status === 409)
  technicalErrorRate.add(![201, 409, 429].includes(response.status))
  check(response, { '仅成功或座位冲突': result => [201, 409, 429].includes(result.status) })
}
