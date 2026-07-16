# open-app-ls 项目架构走读报告

> **日期**: 2026-07-16  
> **走读范围**: 全量源码（9 个子项目）  
> **走读方式**: 只读代码 + 配置 + 文档，提炼事实，标注来源

---

## 一、产品定位

**open-app-ls** 是 **XXX 通讯系统** 的**开放平台**——将通讯系统内部的 IM、会议、云盘、日历、通讯录、邮件、在线文档、机器人、在线状态、VoIP 等能力，以统一、安全、可治理的方式向**企业内部第三方应用**开放。

**来源**: [docs/业务架构.md], [.sddu/specs-tree-root/specs-tree-capability-open-platform/spec.md]

---

## 二、三大平台支柱

| 支柱 | 阶段 | 优先级 | 开放对象 | 依赖关系 |
|------|------|--------|----------|----------|
| **能力开放平台** (Capability Open) | Phase 1 MVP | P0 | API / 事件 / 回调资源 | 基石——支撑另外两个平台 |
| **连接器平台** (Connector) | Phase 1 可选 | P1 | 预构建连接器 + 编排流 | 复用能力平台的 Scope 权限和审批 |
| **数据开放平台** (Data Open) | Phase 2 | P0 | 数据对象 + 数据服务 | 复用能力平台的权限模型、审批、通道 |

**来源**: [.sddu/specs-tree-root/specs-tree-capability-open-platform/spec.md], [plan.md], [.sddu/specs-tree-root/specs-tree-connector-platform/spec.md], [.sddu/specs-tree-root/specs-tree-data-open-platform/spec.md]

---

## 三、服务拓扑（9 个子项目）

```
┌────────────────────────────────────────────────────────────────────┐
│                          前端层                                     │
│                                                                    │
│  wecodesite (开发者后台)          market-web (管理/市场门户)          │
│  React 18 + JSX + Redux          React 18 + TS + Zustand           │
│  Ant Design 4 + Vite 5           Ant Design 4 + Vite 5             │
│  :5173 → proxy :18080            :13000 → proxy :18080             │
│  ─ 应用管理/API/事件/回调         ─ LookUp/字典/审批管理             │
│  ─ 连接器/连接流编排              ─ 聊天机器人绑定                   │
│  ─ 版本发布/成员/能力                                               │
│  ─ Admin 管理区                                                    │
│                                                                    │
│  wecodesiteDemo (连接器平台静态原型, HTML/CSS/JS, 无后端)           │
│  front/ (AI 辅助开发方法论 & 技能集, 非可运行应用)                  │
└───────────────┬─────────────────────────┬──────────────────────────┘
                │ /service/open/v2/*       │ /market-web/service/*
                ▼                          ▼
┌───────────────────────────────────────────────────────────────────────┐
│                         后端服务层                                     │
│                                                                       │
│  open-server (:18080)              market-server (:18080/18083)       │
│  管理服务 - 能力+连接器              管理服务 - 字典/LookUp/审批       │
│  Spring Boot 3.5.14 / Java 17      Spring Boot 3.4.6 / Java 21      │
│  MyBatis + MySQL + Redis             MyBatis + MySQL + Redis          │
│  19 个业务模块, ~120+ 端点           4 个业务模块, ~22 端点            │
│                                                                       │
│  api-server (:18081)               event-server (:18082)              │
│  API 认证鉴权网关                    事件/回调消费网关                 │
│  Spring Boot 3.4.6                  Spring Boot 3.4.6                 │
│  MyBatis + MySQL + Redis             无数据库, 纯 Redis               │
│                                                                       │
│  connector-api (:18180)                                               │
│  连接器运行时引擎                                                       │
│  Spring Boot 3.5.14 / Java 21                                        │
│  WebFlux + R2DBC + Reactive Redis (全响应式)                           │
└───────────────────────────────────────────────────────────────────────┘
                │                              │
                ▼                              ▼
       ┌────────────────┐            ┌─────────────────┐
       │  MySQL 8.x     │            │  Redis Cluster   │
       │  (openapp DB)  │            │  (6 nodes)       │
       │  ~30+ 张表     │            │  缓存/会话       │
       └────────────────┘            └─────────────────┘
```

---

## 四、后端服务详解

### 4.1 open-server (:18080) — 管理服务

**定位**: 能力开放平台 + 连接器平台的管理面，是整个系统的核心管理服务。

**技术栈**: Spring Boot 3.5.14 / Java 17 / MyBatis + MySQL + Redis Cluster

**来源**: [open-server/pom.xml], [open-server/src/main/resources/application.yml]

#### 包结构

```
com.xxx.it.works.wecode.v2
├── common/                          # 共享基础设施
│   ├── annotation/                  # @AuditLog 自定义注解
│   ├── config/                      # 9 个配置类 (Redis, MyBatis, Jackson, WebMVC, Async, HttpClient, ID)
│   ├── constants/                   # CommonConstants
│   ├── context/                     # UserContextHolder (ThreadLocal)
│   ├── controller/                  # HealthController, LookupController
│   ├── enums/                       # AuthTypeEnum, StatusEnum, FlowLifecycleStatus, ConnectorStatus 等
│   ├── exception/                   # BusinessException, GlobalExceptionHandlerV2
│   ├── file/                        # 文件上传子模块
│   ├── id/                          # ID 生成策略 (IdGeneratorStrategy / Dev / Standard)
│   ├── interceptor/                 # UserResolveInterceptor, ServiceLogAspect, OperateLogV2Aspect
│   ├── model/                       # ApiResponse<T>, UserContext, ErrorInfo
│   ├── security/                    # @PlatformAdminPermission + AOP
│   ├── snapshot/                    # EntitySnapshotLoader
│   ├── user/                        # 用户解析策略 (DevUser / StandardUser)
│   └── util/                        # JsonUtils, CommonUtils
│
└── modules/                         # 19 个业务模块
    ├── ability/                     # 应用能力订阅
    ├── api/                         # API 资源注册 & 生命周期
    ├── app/                         # 应用管理 (CRUD, 身份, EAMAP 绑定)
    ├── approval/                    # 审批流模板 + 审批执行引擎
    ├── auditlog/                    # 审计日志
    ├── callback/                    # 回调资源管理
    ├── card/                        # 卡片生命周期设置
    ├── category/                    # 分类树 CRUD + 负责人
    ├── connector/                   # 连接器实体 CRUD + 生命周期
    ├── connectorversion/            # 连接器版本管理 (多版本, 发布/复制/失效)
    ├── debug/                       # 调试代理 → connector-api
    ├── employee/                    # 员工实体
    ├── event/                       # 事件资源管理
    ├── flow/                        # 连接流 CRUD + 生命周期
    ├── flowexecrecord/              # 连接流执行记录
    ├── flowversion/                 # 连接流版本管理
    ├── lookup/                      # 灰度发布白名单
    ├── member/                      # 应用成员管理
    ├── permission/                  # 权限订阅管理
    ├── security/                    # 数据隔离 + 白名单拦截
    ├── sync/                        # 数据迁移 & 回滚
    ├── trigger/                     # 触发器模型
    └── version/                     # 应用版本管理
```

**来源**: [open-server/src/main/java/com/xxx/it/works/wecode/v2/]

#### 端点统计（23 个 Controller, ~120+ 端点）

| Controller | 基础路径 | 端点数 | 核心功能 |
|-----------|---------|--------|---------|
| CategoryController | `/categories` | 8 | 分类树 CRUD + 负责人管理 |
| ApiController | `/apis` | 6 | API 注册/编辑/删除/撤回 |
| EventController | `/events` | 6 | 事件注册/编辑/删除/撤回 |
| CallbackController | `/callbacks` | 6 | 回调注册/编辑/删除/撤回 |
| PermissionController | 多路径 | 18 | API/事件/回调权限订阅/退订/配置/删除 |
| ApprovalController | `/approval-flows`, `/approvals` | 13 | 审批模板 CRUD + 审批执行 |
| AppController | `/app` | 11 | 应用 CRUD + 身份 + EAMAP |
| MemberController | `/member` | 5 | 成员管理 |
| VersionController | `/version` | 7 | 应用版本管理 |
| ConnectorController | `/connectors` | 7 | 连接器 CRUD + 生命周期 |
| ConnectorVersionController | `/connectors/{id}/versions` | 9 | 连接器版本管理 |
| FlowController | `/flows` | 11 | 连接流 CRUD + 部署/启停 |
| FlowVersionController | `/flows/{id}/versions` | 11 | 连接流版本管理 |
| ExecutionRecordController | `/executions` | 2 | 执行记录查询 |
| OpDebugProxyController | `/flows/{id}/versions/{id}/debug` | 1 | 调试代理 → connector-api |
| 其他 (Health, File, Sync, Ability, OperateLog, Lookup) | — | ~15 | 辅助功能 |

**来源**: [open-server/src/main/java/com/xxx/it/works/wecode/v2/modules/*/controller/]

#### 数据库迁移

| 文件 | 内容 |
|------|------|
| `V1__init_capability_open_platform_schema.sql` | 15 张表：分类、API、事件、回调、权限、订阅、审批 |
| `V2__init_connector_platform_schema.sql` | 连接器、连接流表 |
| `V3__connector_platform_v3_schema.sql` | 多版本模型：连接器版本、流版本、执行记录 |

**来源**: [open-server/src/main/resources/db/migration/]

---

### 4.2 api-server (:18081) — API 认证鉴权网关

**定位**: API 请求的认证鉴权入口、审批卡片回调处理、Scope 用户授权、数据查询（供 event-server 调用）。

**技术栈**: Spring Boot 3.4.6 / MyBatis + MySQL + Redis

**来源**: [api-server/pom.xml], [api-server/src/main/resources/application.yml]

#### 包结构

```
com.xxx.api
├── approval/                        # 审批卡片回调 (IM 平台)
│   ├── controller/                  # ApprovalCallbackController
│   ├── dto/                         # 审批请求/响应 DTO
│   ├── entity/                      # ApprovalRecord, ApprovalLog, ApprovalNode
│   ├── handler/                     # 策略模式: Api/Event/Callback PermissionApplyHandler
│   ├── mapper/                      # MyBatis Mapper
│   └── service/                     # ApprovalCallbackService
├── gateway/                         # API 网关
│   ├── controller/                  # ApiGatewayController (ANY /gateway/api/**)
│   ├── dto/                         # ApiGatewayRequest/Response, PermissionCheck*, CallbackConfig*
│   └── service/                     # ApiGatewayService (验签 + 权限校验 + Scope 查找)
├── scope/                           # Scope 用户授权
│   ├── controller/                  # ScopeController (CRUD /api/v1/user-authorizations)
│   ├── dto/                         # UserAuthorization*DTO
│   ├── entity/                      # UserAuthorization
│   ├── mapper/                      # UserAuthorizationMapper
│   └── service/                     # ScopeService
├── data/                            # 数据查询 (供 event-server 调用)
│   ├── controller/                  # DataQueryController (权限校验/订阅查询)
│   └── service/                     # DataQueryService
└── common/                          # 公共基础设施
    ├── config/                      # RedisConfig, JacksonConfig
    ├── controller/                  # HealthController
    ├── entity/                      # Permission, Subscription
    ├── exception/                   # BusinessException, GlobalExceptionHandler
    ├── mapper/                      # PermissionMapper, SubscriptionMapper
    ├── model/                       # ApiResponse<T>
    ├── service/                     # ApplicationService (接口) + Mock 实现
    └── util/                        # SignatureUtil (HMAC-SHA256 AKSK 验签)
```

**来源**: [api-server/src/main/java/com/xxx/api/]

#### 端点清单

| Controller | 端点 | 功能 |
|-----------|------|------|
| ApiGatewayController | `ANY /gateway/api/**` | API 请求代理与鉴权 |
| ApiGatewayController | `POST /gateway/assistant/callbacks/config` | 回调配置查询 |
| ApprovalCallbackController | `POST /api/v1/approvals/callback` | 审批卡片回调 |
| ScopeController | `GET/POST/DELETE /api/v1/user-authorizations` | 用户授权管理 |
| DataQueryController | `GET /gateway/permissions/check` | 权限校验 |
| DataQueryController | `GET /gateway/permissions/subscribers` | 订阅应用查询 |
| DataQueryController | `GET /gateway/subscriptions/config` | 订阅配置查询 |
| DataQueryController | `GET /gateway/permissions/detail` | 权限详情查询 |

**来源**: [api-server/src/main/java/com/xxx/api/*/controller/]

#### 关键设计：审批策略模式

```
ApprovalCallbackHandler (接口)
├── ApiPermissionApplyHandler      → businessType = "api_permission_apply"
├── EventPermissionApplyHandler    → businessType = "event_permission_apply"
└── CallbackPermissionApplyHandler → businessType = "callback_permission_apply"

ApprovalCallbackHandlerFactory     → @PostConstruct 自动收集, O(1) 查找
```

**来源**: [api-server/src/main/java/com/xxx/api/approval/handler/]

---

### 4.3 event-server (:18082) — 事件/回调消费网关

**定位**: 接收业务事件和回调触发，按订阅关系分发给消费者。**无独立数据库**，纯 Redis 缓存 + REST 调用 api-server 获取数据。

**技术栈**: Spring Boot 3.4.6 / Redis + RestTemplate (无 MySQL)

**来源**: [event-server/pom.xml], [event-server/src/main/resources/application.yml]

#### 数据流

```
Provider ──POST /gateway/events/publish──→ EventGatewayService
                                             ├─ 调 api-server 验证 topic
                                             ├─ 查 Redis 缓存获取订阅者 (TTL=300s)
                                             ├─ Channel 0: MQ (stub)
                                             └─ Channel 1: WebHook (async POST)

Provider ──POST /gateway/callbacks/invoke──→ CallbackGatewayService
                                              ├─ 调 api-server 验证 scope
                                              ├─ 查 Redis 缓存获取订阅者
                                              ├─ Channel 0: WebHook
                                              ├─ Channel 1: SSE
                                              └─ Channel 2: WebSocket
```

**来源**: [event-server/src/main/java/com/xxx/event/gateway/service/]

#### 端点清单

| 端点 | 功能 |
|------|------|
| `POST /gateway/events/publish` | 发布事件 |
| `POST /gateway/callbacks/invoke` | 触发回调 |
| `GET /sse/connect/{connectionId}` | 建立 SSE 连接 |
| `DELETE /sse/disconnect/{connectionId}` | 断开 SSE |
| `/ws/{connectionId}` | WebSocket 升级端点 |
| `DELETE /gateway/events/cache/{topic}` | 清除事件订阅缓存 |
| `DELETE /gateway/callbacks/cache/{scope}` | 清除回调订阅缓存 |

**来源**: [event-server/src/main/java/com/xxx/event/gateway/controller/], [event-server/src/main/java/com/xxx/event/common/controller/]

#### 认证框架（7 种类型，大部分为 stub）

```
AuthTypeEnum: COOKIE(0), SOA(1), APIG(2), IAM(3), NONE(4), AKSK(5), CLITOKEN(6)
CredentialProviderImpl → 全部 TODO stub, 返回 null
```

**来源**: [event-server/src/main/java/com/xxx/event/common/auth/]

---

### 4.4 connector-api (:18180) — 连接器运行时引擎

**定位**: 连接流的 HTTP 触发执行引擎。**全响应式**（WebFlux + R2DBC + Reactive Redis）。不维护 DDL，共享 open-server 的数据库表。

**技术栈**: Spring Boot 3.5.14 / Java 21 / WebFlux + R2DBC + Reactive Redis

**来源**: [connector-api/pom.xml], [connector-api/src/main/resources/application.yml]

#### 执行引擎架构

```
POST /api/v1/flows/{flowId}/invoke (HTTP 触发)
  │
  ▼
FlowInvokeService (5 阶段流水线)
  ├─ Phase 1: 凭证认证 (UnifiedCredentialProcessor → CredentialSupplierRegistry)
  ├─ Phase 2: 版本配置解析 (VersionConfigResolver → EntityCacheManager)
  ├─ Phase 3: 触发器鉴权
  ├─ Phase 4: 限流 (InboundRateLimiter → Redis INCR/DECR)
  └─ Phase 5: 缓存检查 → DagScheduler → 缓存写回
                     │
                     ▼
              DagScheduler (DAG 调度)
              ├─ 串行: flatMap 顺序执行
              └─ 并行: Flux.merge() (最多 8 分支)
                     │
                     ▼
              5 种节点执行器
              ├─ TriggerNodeExecutor      (HTTP 入口)
              ├─ ConnectorNodeExecutor    (出站 HTTP, WebClient)
              ├─ DataProcessorNodeExecutor (字段映射)
              ├─ ScriptNodeExecutor       (GraalJS 沙箱, 5 层安全)
              └─ ExitNodeExecutor         (响应组装)
```

**来源**: [connector-api/src/main/java/com/xxx/it/works/wecode/v2/modules/runtime/]

#### GraalJS 沙箱安全（5 层）

| 层 | 措施 |
|---|------|
| 1 | 禁止 IO (无文件系统/网络) |
| 2 | 禁止线程创建 |
| 3 | 禁止 Native 访问 |
| 4 | 限制 Host 访问 |
| 5 | 10,000 语句上限 (防死循环) |

**来源**: [connector-api/src/main/java/com/xxx/it/works/wecode/v2/modules/script/]

#### 缓存策略

| 缓存 | Key 模式 | TTL | 特殊机制 |
|------|---------|-----|---------|
| 实体缓存 | `cp:entity:flow:{id}` | 7 天 ± 2h 随机抖动 | 反雪崩 (anti-thundering-herd) |
| 流结果缓存 | `cp:cache:flow:{flowId}:{cacheKey}` | 可配置 | 表达式模板解析 cache key |

**来源**: [connector-api/src/main/java/com/xxx/it/works/wecode/v2/modules/cache/]

---

### 4.5 market-server (:18080) — 管理服务（字典/LookUp/审批）

**定位**: 管理面补充服务，负责数据字典、LookUp 分类与条目管理、应用版本审批、聊天机器人绑定。

**技术栈**: Spring Boot 3.4.6 / Java 21 / MyBatis + MySQL + Redis + Apache POI

**来源**: [market-server/pom.xml], [market-server/src/main/resources/application.yml]

#### 4 个业务模块

| 模块 | 功能 | 端点数 |
|------|------|--------|
| **approval** | 应用版本发布审批（策略模式: ApprovalEngine → ApprovalHandler） | 3 |
| **chatbotbindtab** | 聊天机器人账号绑定（调 WeContact API 验证） | 3 |
| **dictionary** | 数据字典 CRUD（path + code 唯一） | 5 |
| **lookup** | LookUp 分类 + 条目 CRUD（层级: 分类 → 条目，级联删除） | 6 |

**来源**: [market-server/src/main/java/com/xxx/it/works/wecode/v2/modules/]

---

## 五、前端项目详解

### 5.1 wecodesite — 开发者后台（主生产应用）

| 维度 | 值 |
|------|-----|
| **框架** | React 18 + JSX (非 TypeScript) |
| **构建** | Vite 5 |
| **UI** | Ant Design 4 |
| **状态管理** | Redux (@reduxjs/toolkit) |
| **路由** | react-router-dom v6 |
| **代码编辑器** | Monaco Editor |
| **流编辑器** | @xyflow/react v12 (React Flow) |
| **开发端口** | 5173 |
| **API 代理** | `/service/open/v2` → `http://localhost:18080/open-server` |

**来源**: [wecodesite/package.json], [wecodesite/vite.config.js]

#### 页面路由

| 路由 | 组件 | 功能 |
|------|------|------|
| `/appList` | AppList | 应用列表（首页） |
| `/basic-info` | BasicInfo | 应用基本信息 |
| `/members` | Members | 成员管理 |
| `/capabilities` | Capabilities | 应用能力管理 |
| `/api-management` | ApiManagement | API 资源 & 权限申请 |
| `/events` | Events | 事件订阅配置 |
| `/callbacks` | Callbacks | 回调管理 |
| `/operation-log` | OperationLog | 操作审计日志 |
| `/version-release` | VersionRelease | 版本发布 |
| `/connectorList` | ConnectorList | 连接器列表 |
| `/connectorEditor` | ConnectorEditor | 连接器配置编辑器 |
| `/flowList` | FlowList | 连接流列表 |
| `/flowEditor` | FlowEditorV2 | 连接流可视化编辑器 (步骤向导) |
| `/admin/categories` | CategoryList | 管理端: 分类管理 |
| `/admin/apis` | ApiList | 管理端: API 管理 |
| `/admin/events` | EventList | 管理端: 事件管理 |
| `/admin/callbacks` | CallbackList | 管理端: 回调管理 |
| `/admin/approvals` | ApprovalCenter | 管理端: 审批中心 |

**来源**: [wecodesite/src/router/]

### 5.2 market-web — 管理/市场门户

| 维度 | 值 |
|------|-----|
| **框架** | React 18 + TypeScript |
| **状态管理** | Zustand |
| **开发端口** | 13000 |
| **API 代理** | `/market-web/service` → `http://localhost:18080/market-server` |

#### 页面路由

| 路由 | 功能 |
|------|------|
| `/` | 仪表盘 (API/事件/回调/应用统计) |
| `/lookup-classify` | LookUp 分类管理 |
| `/lookup-item` | LookUp 条目管理 |
| `/dictionary` | 数据字典管理 |
| `/approval` | 审批管理 |

**来源**: [market-web/package.json], [market-web/src/]

### 5.3 wecodesiteDemo — 连接器平台静态原型

纯 HTML/CSS/JS 静态原型，无框架。包含 6 个页面：连接器列表/编辑、连接流列表/编辑、审批中心、运行日志。作为 UX 设计参考。

**来源**: [wecodesiteDemo/]

### 5.4 front/ — AI 辅助开发方法论

非可运行应用。包含 AI Agent 技能集（brainstorming, TDD, code-review 等 14 个子技能）和开发治理流程文档。定义如何构建其他前端项目。

**来源**: [front/README.md], [front/superpowers/]

---

## 六、技术栈总结

| 层次 | 技术 |
|------|------|
| **后端语言** | Java 17 / Java 21 |
| **后端框架** | Spring Boot 3.4.6 / 3.5.14 |
| **ORM** | MyBatis (open-server, market-server, api-server) / R2DBC (connector-api) |
| **数据库** | MySQL 5.7/8.0 (共享 `openapp` 库) |
| **缓存** | Redis 6.0/7.0, Cluster 模式 (6 节点) |
| **响应式** | WebFlux + R2DBC + Reactive Redis (仅 connector-api) |
| **前端框架** | React 18 + Ant Design 4 + Vite 5 |
| **状态管理** | Redux (wecodesite) / Zustand (market-web) |
| **可视化画布** | @xyflow/react v12 (React Flow) |
| **脚本沙箱** | GraalVM Polyglot + GraalJS |
| **API 文档** | SpringDoc OpenAPI 2.5.0 |
| **ID 策略** | 雪花算法 (Snowflake), Profile 感知 |
| **部署** | Spring Boot JAR + Nginx 静态 |

---

## 七、核心设计模式

| 模式 | 应用 | 来源 |
|------|------|------|
| **资源 + 权限解耦** | 每个可开放资源映射为独立 Permission 实体，Scope 全局唯一标识 | [spec.md ADR-003] |
| **订阅消费模型** | 消费者订阅 Permission，网关基于订阅关系鉴权分发 | [ApiGatewayService.java:60-61] |
| **动态审批引擎** | 可配置审批流 (全局默认 + 资源级覆盖)，批量操作 | [open-server/.../approval/] |
| **主表 + 属性表 (KV)** | 业务对象可扩展属性，无需 DDL | [open-server/.../entity/*Property.java] |
| **雪花 ID** | 应用层生成 BIGINT 主键 | [open-server/.../common/id/] |
| **双语字段** | 所有名称/描述均含 `_cn`/`_en` | 全项目统一 |
| **策略模式** | ID 生成器、用户解析、审批处理器、认证凭据均按环境/类型分派 | 各服务 common 包 |
| **Mock/Real 切换** | Spring Profile + 接口抽象 + 契约测试 | [spec.md ADR-002] |
| **AOP 横切** | @AuditLog 审计、@PlatformAdminPermission 授权、ServiceLogAspect | [open-server/.../common/interceptor/] |

---

## 八、关键架构决策 (ADR)

| ADR | 决策 | 理由 | 来源 |
|-----|------|------|------|
| **ADR-001** 单体 + 模块化 | Spring Boot 单体，包级模块隔离 | MVP 速度 (80 人天 vs 145 人天) | [.sddu/.../ADR-001.md] |
| **ADR-002** Mock 隔离 | 接口抽象 + 双实现 + Profile 切换 | 零依赖开发 | [.sddu/.../ADR-002.md] |
| **ADR-003** 权限资源抽象 | Permission 独立实体，与 API/事件/回调解耦 | 一资源多权限，统一订阅 | [.sddu/.../ADR-003.md] |
| **ADR-004** 通道地址白名单 | 平台级 URL 正则白名单防 SSRF | 写时校验，零 DDL | [.sddu/.../capability/ADR-003.md] |
| **连接器 ADR-001** | 自研轻量顺序执行引擎 | 精确匹配 MVP，无框架开销 | [.sddu/.../connector/ADR-001.md] |
| **连接器 ADR-002** | @xyflow/react v12 画布 | React 原生，~30KB，受控模式 | [.sddu/.../connector/ADR-002.md] |
| **连接器 ADR-003** | connector-api 独立部署 | 独立扩缩，故障隔离 | [.sddu/.../connector/ADR-003.md] |

---

## 九、服务间通信

```
wecodesite ──proxy──→ open-server (:18080) ──REST──→ connector-api (:18180) [debug 代理]
market-web ──proxy──→ market-server (:18080)

event-server (:18082) ──REST──→ api-server (:18081) [查询权限/订阅]
api-server (:18081)  ──REST──→ 内部中台网关 (当前 Mock)

connector-api (:18180) ──R2DBC──→ MySQL (共享 openapp 库)
  - 只读: flow, connector_version 表
  - 只写: execution_record, execution_step 表
```

---

## 十、数据库表分布

### 10.1 open-server 管理的表 (openplatform_v2_ 前缀)

| 表 | 模块 | 说明 |
|---|------|------|
| `openplatform_v2_category_t` | category | 分类树节点 |
| `openplatform_v2_category_owner_t` | category | 分类负责人 |
| `openplatform_v2_api_t` | api | API 资源 |
| `openplatform_v2_api_property_t` | api | API 扩展属性 |
| `openplatform_v2_event_t` | event | 事件资源 |
| `openplatform_v2_callback_t` | callback | 回调资源 |
| `openplatform_v2_permission_t` | permission | 权限资源 (Scope) |
| `openplatform_v2_subscription_t` | permission | 订阅关系 |
| `openplatform_v2_approval_flow_t` | approval | 审批流模板 |
| `openplatform_v2_approval_record_t` | approval | 审批记录 |
| `openplatform_v2_approval_log_t` | approval | 审批日志 |
| `openplatform_v2_cp_connector_t` | connector | 连接器 |
| `openplatform_v2_cp_connector_version_t` | connectorversion | 连接器版本 |
| `openplatform_v2_cp_connector_version_ref_t` | connectorversion | 连接器版本引用 |
| `openplatform_v2_cp_flow_t` | flow | 连接流 |
| `openplatform_v2_cp_flow_version_t` | flowversion | 连接流版本 |
| `openplatform_v2_cp_execution_record_t` | flowexecrecord | 执行记录 |
| `openplatform_v2_cp_execution_step_t` | flowexecrecord | 执行步骤 |

### 10.2 共享基础表 (无前缀 v2)

| 表 | 管理服务 | 说明 |
|---|---------|------|
| `openplatform_app_t` | open-server | 应用实体 |
| `openplatform_app_identity_t` | open-server | 应用凭证 (AK/SK) |
| `openplatform_app_p_t` | open-server | 应用扩展属性 |
| `openplatform_app_ability_relation_t` | open-server | 应用-能力关系 |
| `openplatform_app_version_t` | market-server | 应用版本 |
| `openplatform_ability_t` | open-server | 能力定义 |
| `openplatform_property_t` | market-server | 数据字典 |
| `openplatform_lookup_classify_t` | market-server | LookUp 分类 |
| `openplatform_lookup_item_t` | market-server | LookUp 条目 |
| `openplatform_v2_user_authorization_t` | api-server | 用户授权 |

### 10.3 表设计规范约定

| 约定 | 说明 |
|------|------|
| 主键 | Snowflake BIGINT，应用层生成 |
| 字符集 | utf8mb4 |
| 时间精度 | DATETIME(3) 毫秒 |
| 审计字段 | create_by, create_time, last_update_by, last_update_time |
| 枚举 | TINYINT(10) |
| 外键 | 无物理 FK，应用层维护 |
| 双语 | name_cn / name_en 成对出现 |

---

## 十一、应用类型体系

### 11.1 枚举定义

```java
// [open-server/.../modules/app/enums/AppTypeEnum.java]
AppTypeEnum:
  PERSONAL(0, "个人应用")
  BUSINESS(1, "业务应用")

// [open-server/.../modules/app/enums/AppSubTypeEnum.java]
AppSubTypeEnum:
  LEGACY_PERSONAL(0, "存量个人应用")
  PLUGIN(1, "技能")
  PERSONAL_ASSISTANT(2, "个人助理")
  BUSINESS_ASSISTANT(3, "业务助理")
  BUSINESS_STANDARD(4, "业务应用-标准")
```

### 11.2 当前创建逻辑

所有新建应用**硬编码**为 `BUSINESS + BUSINESS_STANDARD`：

```java
// [open-server/.../modules/app/service/impl/AppServiceImpl.java:583-584]
app.setAppType(AppTypeEnum.BUSINESS.getCode());
app.setAppSubType(AppSubTypeEnum.BUSINESS_STANDARD.getCode());
```

### 11.3 权限树隔离

| 权限树 alias | 适用类型 | 认证方式 |
|-------------|----------|----------|
| `api_business_app_soa` | 业务应用 - 应用身份 | SOA |
| `api_business_app_apig` | 业务应用 - 应用身份 | APIG |
| `api_business_user_soa` | 业务应用 - 用户身份 | SOA |
| `api_business_user_apig` | 业务应用 - 用户身份 | APIG |
| `api_personal_user_aksk` | 个人应用 - 用户身份 | AKSK |

**来源**: [docs/sql/02-insert-default-data.sql:25-50]

---

## 十二、认证体系

### 12.1 认证类型枚举

```java
// 各服务统一定义
AuthTypeEnum:
  COOKIE(0)    // Cookie 认证
  SOA(1)       // SOA Token
  APIG(2)      // API 网关认证
  IAM(3)       // IAM 认证
  NONE(4)      // 免认证
  AKSK(5)      // AKSK 签名认证
  CLITOKEN(6)  // CLI Token
```

**来源**: [api-server/.../common/auth/AuthTypeEnum.java], [event-server/.../common/auth/AuthTypeEnum.java]

### 12.2 当前网关鉴权状态

| 环节 | 状态 | 说明 |
|------|------|------|
| `verifyApplication()` | ⚠️ Mock | 仅校验非空，始终返回 true |
| `findScopeByPathAndMethod()` | ⚠️ Mock | 字符串拼接，无 API 资源表查询 |
| `SignatureUtil.verifyAKSKSignature()` | ✅ 已实现 | HMAC-SHA256，5 分钟防重放，但未接入网关 |
| 请求转发 | ⚠️ Mock | 直接返回 200，无实际转发 |

**来源**: [api-server/.../gateway/service/ApiGatewayService.java], [api-server/.../common/util/SignatureUtil.java]

---

## 十三、当前状态 & 待完成项

| 领域 | 状态 | 说明 |
|------|------|------|
| 能力开放平台管理端 (open-server) | ✅ 基本完成 | 19 模块，120+ 端点，Flyway V1–V3 |
| 连接器运行时 (connector-api) | ✅ 核心完成 | DAG 引擎、5 种节点、限流、缓存、沙箱 |
| API 网关 (api-server) | 🟡 部分 Mock | AKSK 验证/网关转发/ApplicationService 待对接 |
| 事件网关 (event-server) | 🟡 部分 Mock | 内部 MQ 通道为 stub，凭据获取为 stub |
| 管理门户 (market-server) | ✅ 功能完成 | 无单元测试，@AuthRole 为 no-op |
| 认证凭据 | 🔴 大面积 stub | 7 种认证类型中仅 COOKIE 和 NONE 完整实现 |
| 数据开放平台 | 📋 仅规划 | 有 spec 文档，无代码实现 |
| 个人应用能力 | 📋 规划中 | 权限树已定义，无实现 |

---

## 十四、部署配置

### 14.1 端口分配

| 服务 | 端口 | Context Path |
|------|------|-------------|
| open-server | 18080 | `/open-server` |
| market-server | 18080 (同 Nginx) | `/market-server` |
| api-server | 18081 | `/api-server` |
| event-server | 18082 | `/event-server` |
| connector-api | 18180 | 无 |
| wecodesite (dev) | 5173 | — |
| market-web (dev) | 13000 | `/market-web` |

### 14.2 环境 Profile

| 服务 | dev | prod |
|------|-----|------|
| MySQL | 192.168.3.155:3306/openapp | 环境变量注入 |
| Redis | Cluster 6 节点 (201-206) | Cluster + 密码环境变量 |
| 日志 | DEBUG | INFO |
| ID 生成 | DevIdGeneratorStrategy (顺序) | StandardIdGeneratorStrategy (雪花) |
| 用户解析 | DevUserStrategy (Cookie) | StandardUserStrategy (标准认证) |

---

## 十五、测试体系

| 服务 | Java 单元测试 | Python 集成测试 | Shell API 测试 | E2E 测试 |
|------|-------------|----------------|---------------|---------|
| open-server | 31 个类 | ~55 个文件 | 58 个脚本 | — |
| api-server | 11 个类 | — | — | — |
| event-server | 14 个类 | — | — | — |
| connector-api | 21 个类 | 17 个文件 | — | — |
| market-server | ❌ 无 | — | — | — |
| market-web | — | — | — | ~25 个 Puppeteer 脚本 |
| wecodesite | Jest 配置 | — | — | — |

**来源**: 各服务 `src/test/` 目录
