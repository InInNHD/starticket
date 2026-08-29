# 压测说明

库存正式测试使用 Java 21 标准库负载客户端和隔离的 Docker Compose 环境，不需要安装 k6：

```powershell
./load/Prepare-Sustained.ps1
./load/Run-Sustained.ps1
```

`Prepare-Sustained.ps1` 会停止默认 demo，删除并重建仅属于 `starticket-perf` 的压测卷，创建 300 个预热专用用户、1000 个正式专用用户、5000 个座位、18 个预热场次和 18 个正式场次。`Run-Sustained.ps1` 完成后会自动清理压测环境并恢复 `http://localhost:8081`；中途终止时可执行：

```powershell
./load/Restore-Demo.ps1
```

## 固定环境

[`compose.perf.yml`](compose.perf.yml) 将服务端限制为 4 vCPU / 8 GB 预算：容器 CPU 上限合计 4 vCPU，内存上限合计 7 GB，另预留 1 GB Docker 开销。后端固定 `-Xms512m -Xmx1g`，HikariCP 最大连接数为 10。负载发生器运行在容器外。

实际容器限制、Git 提交号、JVM 参数、数据规模和命令写入 [`results/performance-environment.json`](results/performance-environment.json)。

## 库存口径

每个正式请求使用未参与预热的独立用户，每个正式轮次固定 300 并发和 1000 次请求，预热与正式测试使用不同用户池和不同场次：

- `SINGLE`：1000 个请求竞争 1 个座位，验证冲突快速失败；
- `LIMITED`：1000 个请求竞争 100 个座位，验证限量交易能力；
- `SPREAD`：1000 个请求购买 1000 个不同座位，验证正常业务吞吐。

MySQL-only 与 Redis Lua + MySQL 各执行 3 轮。结果分别记录总吞吐、成功吞吐、冲突吞吐、P95、P99、`201/409/429/5xx`、最终售出、重复座位、超卖和资源指标。聚合结果见 [`results/benchmark-summary.json`](results/benchmark-summary.json)，全局断言见 [`results/benchmark-consistency-assertion.txt`](results/benchmark-consistency-assertion.txt)。

单独运行固定库存客户端的格式为：

```powershell
java load/OrderRace.java --inventory BASE_URL `
  '@load/.runtime/tokens.txt' PERFORMANCE_ID '@load/.runtime/seats-100.txt' `
  300 1000 LIMITED load/results/manual.json
```

## 活动详情

```powershell
java load/OrderRace.java --event-details http://localhost:18081 1 500 10 30 load/results/event-details-c500-r1.json
```

也保留了 k6 脚本用于临时接口检查：

```bash
k6 run -e BASE_URL=http://localhost:18080 -e EVENT_ID=1 load/event-details.js
k6 run -e BASE_URL=http://localhost:18080 -e TOKEN=用户JWT -e PERFORMANCE_ID=1 -e SEAT_IDS=1,2,3 load/order-race.js
```

`409` 是库存冲突，不是成功下单。简历和报告必须引用 `successThroughput` 描述成功交易能力，并同时给出状态码数量和最终库存断言。
