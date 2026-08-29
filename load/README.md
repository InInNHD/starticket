# 压测说明

Java 21 标准库版（无需安装 k6）：

```powershell
java load/OrderRace.java http://localhost:18080 "用逗号分隔的JWT" 1 1 100 load/summary.json
```

参数依次为 API 地址、JWT 列表、场次 ID、座位 ID、并发请求数和结果文件。

完整的预热 + 持续压测矩阵（PowerShell 7、Docker Desktop、JDK 21）：

```powershell
./load/Prepare-Sustained.ps1
./load/Run-Sustained.ps1
```

脚本会重建隔离数据库 `starticket_perf`，准备 600 个用户、5000 个座位和 36 个独立场次，然后分别以 MySQL 条件更新和 Redis Lua 预锁两种方案执行：

- `20 / 100 / 300` 并发；
- `HOTSPOT`（竞争同一座位）与 `SPREAD`（轮询不同座位）；
- 每组预热 10 秒、持续 30 秒、执行 3 轮；
- 同步采集进程 CPU、JVM 堆、HikariCP 活跃连接和 Redis 命令量；
- 最后自动执行 SQL 防超卖断言，并恢复默认 demo 服务。

原始结果与中位数汇总写入 `load/results/`，临时 JWT 仅存放在被 Git 忽略的 `load/.runtime/`。可用参数缩短本地冒烟测试：

```powershell
./load/Run-Sustained.ps1 -WarmupSeconds 2 -DurationSeconds 5
```

活动详情（Java 21 标准库，500 并发、10 秒预热、30 秒正式测试）：

```powershell
java load/OrderRace.java --event-details http://localhost:18080 1 500 10 30 load/results/event-details-c500-r1.json
```

也可以使用 k6：

```bash
k6 run -e BASE_URL=http://localhost:18080 -e EVENT_ID=1 load/event-details.js
```

固定库存竞争下单：

```bash
k6 run -e BASE_URL=http://localhost:18080 -e TOKEN=用户JWT -e PERFORMANCE_ID=1 -e SEAT_IDS=1,2,3,4,5 load/order-race.js
```

分别以 `STARTICKET_INFRA_ENABLED=false/true` 启动后执行相同脚本。压测后执行 `load/assert-no-oversell.sql`，结果必须为 `PASS`。业务冲突 409 是预期结果，技术错误率只统计 201/409/429 之外的响应。
