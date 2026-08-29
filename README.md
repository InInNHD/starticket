# StarTicket 城市演出票务平台

StarTicket 是面向城市演出和校园活动的在线票务平台。项目重点不是堆砌中间件，而是完整实现“活动发布 → 选座锁座 → 下单支付 → 电子票 → 入场核销 → 取消退款”的交易闭环，并验证并发下不超卖、异步消息可恢复、关键接口可观测。

## 项目定位

- 求职方向：Java 后端开发
- 架构形态：按业务模块组织的单体应用
- 开发策略：先保证数据库交易闭环，再加入 Redis、RabbitMQ、限流和监控
- 核心难点：座位库存、订单状态、接口幂等、超时释放、支付回调、票码核销

## 已确定技术栈

### 后端

- Java 21
- Spring Boot 3.5.x
- Spring MVC、Spring Validation
- Spring Security + OAuth2 Resource Server（JWT）
- Spring Data JPA
- MySQL 8.4
- Redis 7
- RabbitMQ 4
- Flyway
- Spring Boot Actuator + Micrometer + Prometheus
- springdoc-openapi
- Maven

### 前端与工程化

- Vue 3 + TypeScript + Vite
- Element Plus、Axios
- Docker Compose
- JUnit 5、Testcontainers
- k6 与 Java 21 并发压测脚本
- GitHub Actions

## 系统架构

```mermaid
flowchart LR
    Browser[Vue 3 用户端 / 运营端] -->|JWT + REST| API[Spring Boot 模块化单体]
    API --> Account[账户与权限]
    API --> Event[活动 / 场次 / 票档]
    API --> Order[库存 / 订单 / 支付]
    API --> Ticket[电子票 / 核销]
    Account & Event & Order & Ticket --> MySQL[(MySQL<br/>最终事实来源)]
    Order -->|Lua 预锁 / 限流 / 缓存| Redis[(Redis)]
    Order -->|事务内写 Outbox| Outbox[(Outbox)]
    Outbox -->|可靠投递| RabbitMQ[(RabbitMQ)]
    API -->|Micrometer| Prometheus[Prometheus]
    Prometheus --> Grafana[Grafana]
```

采用模块化单体而不是微服务：账户、活动、库存、订单、支付和票务按包边界隔离，但共享一个进程和数据库事务，便于应届生项目把业务闭环、并发正确性和可观测性做深。

## 核心下单时序

```mermaid
sequenceDiagram
    actor User as 用户
    participant API as Order API
    participant Redis as Redis Lua
    participant DB as MySQL
    participant Outbox as Outbox/RabbitMQ
    User->>API: POST /api/orders + Idempotency-Key
    API->>DB: 查询幂等记录与用户限购数量
    API->>Redis: 原子预锁座位（可降级）
    Redis-->>API: 预锁成功/已被占用
    API->>DB: 条件更新 AVAILABLE → LOCKED
    API->>DB: 创建订单、订单项、幂等记录和 Outbox
    DB-->>API: 同一事务提交
    API-->>User: 201 Created / 409 Conflict
    Outbox-->>API: 延迟关单消息
    API->>DB: 超时订单关闭并释放库存
```

## 页面截图

| 登录与公开活动 | 用户选座 |
|---|---|
| ![StarTicket 登录与活动页](docs/images/home.png) | ![StarTicket 座位选择](docs/images/seat-selection.png) |

| 主办方工作台 | 管理员工作台 |
|---|---|
| ![StarTicket 主办方工作台](docs/images/organizer-dashboard.png) | ![StarTicket 管理员工作台](docs/images/admin-dashboard.png) |

## 第一版模块

```text
account       账户、认证和角色
venue         场馆、区域和座位模板
event         活动、场次和票档
inventory     场次座位、锁座和释放
order         订单、订单项和退款申请
payment       模拟支付和幂等回调
ticket        电子票、票码和入场核销
admin         活动审核和失败消息重放
```

模块使用包边界隔离，不为每个模块单独部署。首版只有一个后端进程、一个 MySQL、一个 Redis 和一个 RabbitMQ。

## 文档

- [需求规格](docs/01-requirements.md)
- [架构与技术方案](docs/02-architecture.md)
- [真实性能测试报告](docs/03-performance-report.md)

## 本地启动

环境要求：JDK 21、Maven 3.9+、Node.js 22+、Docker Desktop。

```bash
# 构建并启动前端、后端、MySQL、Redis、RabbitMQ、Prometheus 和 Grafana
docker compose up -d --build
```

- Web：`http://localhost:8081`
- Swagger：`http://localhost:18080/swagger-ui.html`
- 健康检查：`http://localhost:18080/actuator/health`
- Prometheus：`http://localhost:9090`
- Grafana：`http://localhost:13000`
- RabbitMQ 管理页：`http://localhost:15672`

只进行本地开发时，也可以先执行 `docker compose up -d mysql redis rabbitmq`，然后分别运行 `mvn spring-boot:run` 和 `npm run dev`。

生产或共享环境必须通过 `STARTICKET_JWT_SECRET` 提供至少 32 字节的随机 JWT 密钥；未配置时应用只为本地开发生成一次性随机密钥。

Docker Compose 默认开启本地 demo 数据，四个账号密码均为 `Password123`：`admin`、`organizer`、`checker`、`user`。同时会初始化一个场馆、活动、场次、票档和 18 个座位。共享或生产环境设置 `STARTICKET_DEMO_ENABLED=false`。

如果浏览器提示拒绝连接，先确认 Docker Desktop 已启动，再执行：

```bash
docker compose ps
docker compose logs backend --tail 100
```

当前活动发布链路为：管理员建立场馆/区域/座位模板 → 主办方创建活动草稿 → 添加场次 → 按场馆区域配置票档 → 提交审核 → 管理员通过或驳回 → 普通用户查看已公开活动。

完整交易链路为：用户选择场次与座位 → Redis Lua 预锁 → MySQL 条件锁座 → 幂等创建待支付订单 → Outbox 发布超时消息 → 模拟支付回调 → 座位售出并生成电子票 → 检票员单次核销；取消、超时或退款会按状态释放库存。

## 核心难点与实现

| 难点 | 实现 | 验证方式 |
|---|---|---|
| 并发超卖 | Redis Lua 提前削峰，MySQL 条件更新和唯一约束最终兜底 | 100 线程争同一座位 + SQL 全库断言 |
| 拆单绕过限购 | 事务内统计待支付/已支付票数，结合场次级互斥更新 | 同一用户并发创建订单测试 |
| 重复请求和回调 | 幂等键唯一约束、状态机校验、冲突后回读既有结果 | 并发幂等下单、支付回调、退款和核销测试 |
| 消息可靠性 | 业务事务写 Outbox，原子抢占发布，失败重试、死亡记录与人工重放 | RabbitMQ 中断恢复测试 |
| 多角色运营闭环 | 用户、主办方、管理员、检票员四类权限和审计日志 | Testcontainers 端到端流程测试 |
| 故障定位 | Problem Details 携带 requestId，Prometheus 指标和预置 Grafana 看板 | 集成测试与 Docker 冒烟验证 |

## 设计取舍

- MySQL 是库存最终事实来源，Redis 只在热点抢票时承担预锁和削峰；Redis 不可用时允许降级到数据库条件更新。
- 使用本地事务 + Outbox 保证订单和待发送事件一起提交，接受消息的最终一致性，避免在单体项目中引入分布式事务框架。
- 保留模块化单体，避免为展示技术而拆微服务；当压测证明单体资源或团队边界成为瓶颈时再拆分。
- 支付采用签名模拟回调，重点验证支付单唯一性、回调幂等和订单状态流转，不在求职项目中伪装真实支付资质。

## 压测结论

测试使用 600 个用户、5000 个座位和 36 个独立场次，覆盖 20 / 100 / 300 并发、热点/分散座位、每组 10 秒预热 + 30 秒持续负载 + 3 轮。所有 36 轮技术错误率均为 0，SQL 防超卖断言为 `PASS`。另以 500 并发完成 3 轮热门活动详情测试，共 96049 次正式请求全部返回 200，三轮中位吞吐为 1023.49 req/s。

| 场景 | 并发 | MySQL 吞吐 / P95 | Redis + MySQL 吞吐 / P95 | 结论 |
|---|---:|---:|---:|---|
| 热点座位 | 20 | 612.15 req/s / 51.25 ms | 724.91 req/s / 46.83 ms | Redis 吞吐 +18.4% |
| 热点座位 | 100 | 537.13 req/s / 318.94 ms | 720.23 req/s / 265.78 ms | Redis 吞吐 +34.1% |
| 热点座位 | 300 | 571.32 req/s / 786.32 ms | 810.01 req/s / 665.97 ms | Redis 吞吐 +41.8% |
| 分散座位 | 100 | 1092.50 req/s / 203.34 ms | 729.33 req/s / 285.72 ms | MySQL-only 更合适 |

结论不是“用了 Redis 就更快”：Redis 对热点行竞争有效，但在分散座位场景会增加网络往返与锁补偿成本。完整环境、12 组中位数结果、资源指标和边界说明见[性能测试报告](docs/03-performance-report.md)。

## 测试与验证

```bash
# 21 个流程及真实依赖并发测试
cd backend && mvn test

# 前端类型检查和生产构建
cd frontend && npm ci && npm run build

# 完整持续压测（PowerShell 7）
./load/Prepare-Sustained.ps1
./load/Run-Sustained.ps1

# 热门活动详情 500 并发验收
java load/OrderRace.java --event-details http://localhost:18080 1 500 10 30 load/results/event-details-c500-r1.json
```

## 可直接放入简历的项目描述

- 基于 Java 21、Spring Boot、MySQL、Redis 与 RabbitMQ 实现城市演出票务闭环，覆盖活动审核、选座锁座、幂等下单、支付回调、电子票核销、取消退款及四角色权限。
- 设计 Redis Lua 预锁 + MySQL 条件更新的双层库存方案，以数据库作为最终一致性防线；完成 20/100/300 并发、热点/分散负载共 36 轮测试，热点 300 并发吞吐较 MySQL-only 提升 41.8%，全程无技术错误且 SQL 断言无超卖。
- 使用事务 Outbox、延迟消息、失败重试/死信重放解决订单超时与消息可靠性，并通过 Testcontainers 覆盖并发幂等下单、支付回调、退款和单次核销，接入 Prometheus、Grafana 与 requestId 追踪提升可观测性。

## 当前进度

- [x] Maven、Vue 和 Docker Compose 工程骨架
- [x] Flyway 账户表迁移
- [x] 用户注册、登录、JWT 签发与受保护的当前用户接口
- [x] 注册/登录前端页面
- [x] 账户完整流程集成测试
- [x] 场馆、区域、规则化座位模板和管理页面
- [x] 活动、场次、区域票档、提交审核、审核通过/驳回和公开查询
- [x] 场次座位快照、数据库条件锁座、幂等订单、取消和超时释放
- [x] 模拟支付、签名回调、电子票、单次核销和整单模拟退款
- [x] Redis Lua 预锁、热点缓存、限流和数据库降级
- [x] RabbitMQ 延迟关单、Outbox 重试、死信查询和定时补偿
- [x] Testcontainers 真实 MySQL、Redis、RabbitMQ 并发一致性测试
- [x] Prometheus 业务指标、Grafana 预置仪表盘、Swagger 和 GitHub Actions
- [x] MySQL-only 与 Redis Lua + MySQL 三轮性能对比及 SQL 防超卖断言
- [x] 热门活动详情 500 并发三轮验收，96049 次正式请求无服务端错误
- [x] 活动与订单服务端搜索分页、主办方销售看板和管理员订单查询
- [x] 草稿场次编辑停用、票档编辑启停和运营查询索引
- [x] 活动取消、管理员下架、演出结束后自动结束活动
- [x] Outbox 多实例原子抢占、超时恢复、消费死信落库重放和关键操作审计
- [x] 统一 Problem Details 与 requestId 追踪、Swagger JWT 授权和 Grafana 运行监控
- [x] 21 个高价值流程及真实依赖并发测试，覆盖权限、归属、签名和状态竞争

## 版本边界

首版不接入真实支付、不拆微服务、不做分库分表、不做推荐算法、不支持复杂优惠券。等完整闭环、测试和压测完成后，再根据实际瓶颈决定是否增加。
