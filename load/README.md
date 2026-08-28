# 压测说明

Java 21 标准库版（无需安装 k6）：

```powershell
java load/OrderRace.java http://localhost:8080 "用逗号分隔的JWT" 1 1 100 load/summary.json
```

参数依次为 API 地址、JWT 列表、场次 ID、座位 ID、并发请求数和结果文件。

活动详情：

```bash
k6 run -e EVENT_ID=1 load/event-details.js
```

固定库存竞争下单：

```bash
k6 run -e TOKEN=用户JWT -e PERFORMANCE_ID=1 -e SEAT_IDS=1,2,3,4,5 load/order-race.js
```

分别以 `STARTICKET_INFRA_ENABLED=false/true` 启动后执行相同脚本。压测后执行 `load/assert-no-oversell.sql`，结果必须为 `PASS`。业务冲突 409 是预期结果，技术错误率只统计 201/409/429 之外的响应。
