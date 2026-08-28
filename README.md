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

当前活动发布链路为：管理员建立场馆/区域/座位模板 → 主办方创建活动草稿 → 添加场次 → 按场馆区域配置票档 → 提交审核 → 管理员通过或驳回 → 普通用户查看已公开活动。

完整交易链路为：用户选择场次与座位 → Redis Lua 预锁 → MySQL 条件锁座 → 幂等创建待支付订单 → Outbox 发布超时消息 → 模拟支付回调 → 座位售出并生成电子票 → 检票员单次核销；取消、超时或退款会按状态释放库存。

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
- [x] 活动与订单服务端搜索分页、主办方销售看板和管理员订单查询
- [x] 草稿场次编辑停用、票档编辑启停和运营查询索引
- [x] 活动取消、管理员下架、演出结束后自动结束活动
- [x] Outbox 多实例原子抢占、超时恢复、消费死信落库重放和关键操作审计

## 版本边界

首版不接入真实支付、不拆微服务、不做分库分表、不做推荐算法、不支持复杂优惠券。等完整闭环、测试和压测完成后，再根据实际瓶颈决定是否增加。
