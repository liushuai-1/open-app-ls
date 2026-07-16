# Spec: 个人应用鉴权 (Personal App Authentication)

> **版本**: v0.1-draft  
> **日期**: 2026-07-16  
> **状态**: 待评审  
> **范围**: 鉴权链路 + 凭证生命周期管理

---

## 0. 背景：业务应用鉴权现状

### 0.1 当前网关鉴权链路（业务应用）

以下流程从代码中提取，描述 api-server 当前处理业务应用 API 调用的完整链路。

```
客户端 (业务应用)
  │
  │  HTTP Request
  │  Headers: X-App-Id, X-Auth-Type, Authorization
  │
  ▼
┌─ ApiGatewayController.proxyApiRequest() ──────────────────────────────┐
│  [api-server/.../gateway/controller/ApiGatewayController.java:58-108] │
│                                                                       │
│  Step 1: 验证应用身份                                                  │
│    apiGatewayService.verifyApplication(appId, authType, credential)   │
│    [ApiGatewayController.java:71]                                     │
│    └─→ ApplicationService.verifyApplication()                         │
│        [api-server/.../common/service/ApplicationService.java:34]     │
│        └─→ 当前实现: ApplicationServiceMockImpl                       │
│            [api-server/.../common/service/impl/                        │
│             ApplicationServiceMockImpl.java:48-68]                    │
│            ⚠️ Mock: 仅校验 appId 和 credential 非空，始终返回 true     │
│            [ApplicationServiceMockImpl.java:57-67]                    │
│                                                                       │
│  Step 2: 提取请求路径和方法                                            │
│    extractPath(request) → 移除 /gateway/api 前缀                      │
│    [ApiGatewayController.java:78-79, 113-121]                         │
│                                                                       │
│  Step 3: 查找 Scope                                                   │
│    apiGatewayService.findScopeByPathAndMethod(path, method)           │
│    [ApiGatewayController.java:82]                                     │
│    └─→ 当前实现: Mock，字符串拼接                                      │
│        [ApiGatewayService.java:113-128]                               │
│        ⚠️ Mock: "api:" + path替换 + ":" + method                      │
│                                                                       │
│  Step 4: 校验权限                                                     │
│    apiGatewayService.checkPermission(appId, scope)                    │
│    [ApiGatewayController.java:85-90]                                  │
│    └─→ permissionMapper.selectByScope(scope)                          │
│        [ApiGatewayService.java:51]                                    │
│    └─→ subscriptionMapper.selectByAppIdAndPermissionId(appId, pid)    │
│        [ApiGatewayService.java:60-61]                                 │
│    └─→ 检查 subscription.status == 1 (已授权)                         │
│        [ApiGatewayService.java:71]                                    │
│                                                                       │
│  Step 5: 转发请求到内部中台网关                                        │
│    ⚠️ Mock: 直接返回 200 成功响应                                     │
│    [ApiGatewayController.java:92-101]                                 │
└───────────────────────────────────────────────────────────────────────┘
```

### 0.2 已有但未使用的组件

| 组件 | 位置 | 状态 |
|------|------|------|
| **AKSK 签名验证** | `SignatureUtil.verifyAKSKSignature()` [api-server/.../common/util/SignatureUtil.java:36-75] | ✅ 已实现，但网关链路中**未调用** |
| **Bearer Token 验证** | `SignatureUtil.verifyBearerToken()` [SignatureUtil.java:143-157] | ⚠️ Mock 实现 |
| **签名算法** | HMAC-SHA256, 签名字符串 = `timestamp\nnonce\nsha256(body)` [SignatureUtil.java:80-90] | ✅ 完整 |
| **防重放** | 5 分钟时间窗口校验 [SignatureUtil.java:47-53] | ✅ 完整 |
| **AppIdentity 表** | `openplatform_app_identity_t`，字段: id, appId, ak, publicKey(即SK), privateKey, keyVersion, status [open-server/.../app/entity/AppIdentity.java] | ✅ 已建表，open-server 管理 |
| **AppIdentityMapper** | `selectByAppId(appId)`, `insert(identity)`, `deleteByAppId(appId)` [open-server/.../app/mapper/AppIdentityMapper.java] | ⚠️ 仅有 select/insert/delete，缺 selectByAk |
| **AuthTypeEnum.AKSK** | 值=5, 已在 api-server/event-server/market-server/open-server 中统一定义 | ✅ 完整 |
| **Subscription.authType** | 字段已支持 `5=AKSK` [api-server/.../common/entity/Subscription.java:30] | ✅ 完整 |
| **个人权限树** | `api_personal_user_aksk` 已在 category 种子数据中定义 [docs/sql/02-insert-default-data.sql:42] | ✅ 已定义，无数据 |

### 0.3 关键 Gap 总结

```
业务应用鉴权链路中，以下环节是 Mock 或缺失的：

1. verifyApplication() → Mock 实现，无真实身份验证
2. findScopeByPathAndMethod() → Mock 实现，无 API 资源表查询
3. 请求转发 → Mock 实现，无实际转发
4. AKSK 签名验证工具已就绪，但未接入网关链路
5. api-server 无 AppIdentity 数据访问能力（Mapper 仅在 open-server）
6. 无凭证生命周期管理（生成/轮换/过期/吊销）
```

---

## 1. Scope

### 1.1 In Scope

| # | 功能 | 说明 |
|---|------|------|
| F1 | **个人应用 AKSK 鉴权链路** | Agent 使用 AKSK 签名调用 `/gateway/api/**`，网关完成签名验证、身份解析、权限校验 |
| F2 | **身份解析方案** | 网关从 AKSK 解析出 appId + userId（用户身份），注入到下游请求 |
| F3 | **凭证生命周期管理** | AKSK 的生成、查询、轮换（重新生成）、吊销、过期机制 |
| F4 | **AKSK 存储复用** | 复用 `openplatform_app_identity_t` 表，api-server 新增 Mapper 读取该表 |

### 1.2 Out of Scope

| # | 功能 | 说明 |
|---|------|------|
| X1 | 权限树数据填充 | `api_personal_user_aksk` 树中挂入哪些 API Scope，属于运营配置 |
| X2 | API 敏感级别分级 | L1/L2/L3 分级规则，属于产品策略 |
| X3 | 限流配额策略 | 个人应用 vs 业务应用的差异化限流，属于后续需求 |
| X4 | 连接器/连接流 | Phase 1 不对个人应用开放 |
| X5 | 前端 UI | 个人应用创建页、AKSK 管理页的 UI 改动 |
| X6 | 审批流变更 | 同等审批，无需修改 |

---

## 2. 身份解析方案对比

### 2.1 背景

业务应用有两种身份模式：
- **应用身份**（SOA/APIG）：代表应用自身，调用组织级 API [docs/sql/02-insert-default-data.sql:26-30]
- **用户身份**（OAuth scope）：代表授权用户，调用用户级 API [docs/sql/02-insert-default-data.sql:34-38]

个人应用只有 AKSK 凭证，但调用 L2 级（个人维度读写）API 时，下游需要知道"调用者是谁"。

### 2.2 方案 A：AKSK 绑定用户

**核心思路：** 每个个人应用在创建时绑定创建者 userId，AKSK 即代表该员工身份。

```
Agent ──(AKSK 签名)──→ api-server 网关
                         │
                         ├─ 1. 从请求头提取 AK (X-Access-Key)
                         ├─ 2. 查 app_identity 表: AK → AppIdentity(appId)
                         ├─ 3. 查 app 表: appId → App(appType=PERSONAL, ownerUserId)
                         ├─ 4. 验证 AKSK 签名 (SignatureUtil)
                         ├─ 5. 查订阅关系: appId + scope → Subscription
                         ├─ 6. 注入用户身份: X-User-Id = ownerUserId
                         └─ 7. 转发请求 + X-User-Id + X-App-Id → 内部网关
```

| 维度 | 评估 |
|------|------|
| **安全** | 🟢 AKSK 与用户 1:1 绑定，泄露影响范围限于单人；无法冒充他人 |
| **高可用** | 🟢 链路最短（2 次查表），无外部依赖，故障点少 |
| **性能** | 🟢 无额外网络开销；app 信息可缓存，查表 ≈ 1ms |
| **对现有功能影响** | 🟢 最小——仅需在 `ApplicationService` 中实现 AKSK 验证分支 + 注入 userId |
| **局限** | 一个个人应用只能代表一个人；多人共用需各自创建应用 |

**改动清单：**
- `ApplicationService` 新增 `AppContext resolveByAksk(String ak, String signature, ...)` 方法
- 返回值携带 `appId` + `userId` + `appType`
- `ApiGatewayController` 在转发时注入 `X-User-Id` 头

### 2.3 方案 B：请求头传 userId

**核心思路：** Agent 在请求头中显式传递 `X-User-Id`，网关校验该 userId 与 appId 的授权关系。

```
Agent ──(AKSK 签名 + X-User-Id: U001)──→ api-server 网关
                                           │
                                           ├─ 1. 从请求头提取 AK + X-User-Id
                                           ├─ 2. 查 app_identity 表: AK → AppIdentity(appId)
                                           ├─ 3. 验证 AKSK 签名
                                           ├─ 4. 查 user_authorization 表:
                                           │     appId + userId + scope → 有效授权？
                                           │     [api-server/.../scope/entity/UserAuthorization.java]
                                           ├─ 5. 查订阅关系: appId + scope → Subscription
                                           ├─ 6. 注入用户身份: X-User-Id = 请求头中的 userId
                                           └─ 7. 转发请求
```

| 维度 | 评估 |
|------|------|
| **安全** | 🟡 需校验 user_authorization 授权关系，否则任何 userId 都可伪造；授权记录可能被篡改 |
| **高可用** | 🟡 多一次 user_authorization 表查询；授权关系需缓存，否则高频查表 |
| **性能** | 🟡 多 1 次查表 (~1ms)；授权关系缓存一致性需维护 |
| **对现有功能影响** | 🟡 需复用 `UserAuthorization` 表 [api-server/.../scope/entity/] 并新增授权关系校验逻辑 |
| **灵活性** | 🟢 一个个人应用可代表多个用户（需提前授权） |

**改动清单：**
- `ApiGatewayController` 新增 `@RequestHeader("X-User-Id")` 参数
- `ApiGatewayService` 新增 `checkUserAuthorization(appId, userId, scope)` 方法
- 查询 `openplatform_v2_user_authorization_t` 表 [api-server]，校验 userId+appId 授权关系
- `UserAuthorization.scopes` 字段 (JSON 数组) [api-server/.../scope/entity/UserAuthorization.java] 需解析比对

### 2.4 方案 C：短期 Token

**核心思路：** AKSK 用于换取短期 `user_access_token`（如 2h 有效期），token 中携带 userId + appId。Agent 后续请求用 token 调用。

```
阶段 1: Token 换取
Agent ──(AKSK 签名)──→ POST /api/v1/auth/token
                         │
                         ├─ 1. 验证 AKSK 签名
                         ├─ 2. 查 app_identity + app 表获取 appId + ownerUserId
                         ├─ 3. 生成 JWT: {appId, userId, scopes, exp=now+2h}
                         └─ 4. 返回 user_access_token

阶段 2: API 调用
Agent ──(Bearer user_access_token)──→ /gateway/api/**
                                       │
                                       ├─ 1. 解析 JWT，验证签名和过期时间
                                       ├─ 2. 从 JWT 提取 appId + userId
                                       ├─ 3. 查订阅关系: appId + scope
                                       ├─ 4. 注入 X-User-Id = JWT.userId
                                       └─ 5. 转发请求

阶段 3: Token 刷新
Agent ──(AKSK 签名)──→ POST /api/v1/auth/token/refresh
                         │
                         └─ 返回新的 user_access_token
```

| 维度 | 评估 |
|------|------|
| **安全** | 🟢 最安全——token 短期有效，AKSK 不随每次 API 调用暴露；JWT 防篡改 |
| **高可用** | 🔴 Token 换取接口成为关键路径；需保证 token 服务高可用，否则所有 Agent 无法工作 |
| **性能** | 🟡 Token 换取增加一次额外请求；但后续 API 调用仅解析 JWT（无查表），性能更好 |
| **对现有功能影响** | 🔴 最大——需新增 token 端点、JWT 签发/验证基础设施、Agent 端 SDK 需实现 token 刷新 |
| **Agent 复杂度** | 🔴 Agent 端需实现 token 管理（获取、缓存、过期前刷新），增加客户端复杂度 |

**改动清单：**
- 新增 `AuthController`: `POST /api/v1/auth/token`, `POST /api/v1/auth/token/refresh`
- 引入 JWT 依赖 (`jjwt` 或 `nimbus-jose-jwt`)
- `ApiGatewayController` 增加 Bearer Token 解析分支
- Agent SDK 需配套 token 管理逻辑

### 2.5 三方案对比总表

| 维度 | 方案 A (AKSK 绑定用户) | 方案 B (请求头传 userId) | 方案 C (短期 Token) |
|------|----------------------|------------------------|---------------------|
| **安全性** | 🟢 高（1:1 绑定） | 🟡 中（需授权校验） | 🟢 最高（短期 token） |
| **高可用** | 🟢 高（链路短） | 🟡 中（多一次查表） | 🔴 低（token 服务依赖） |
| **性能** | 🟢 ~5ms | 🟡 ~6ms | 🟡 首次 8ms / 后续 3ms |
| **改动量** | 🟢 小（3-5 个文件） | 🟡 中（5-8 个文件） | 🔴 大（10+ 文件 + 新依赖） |
| **Agent 复杂度** | 🟢 最低（只签名） | 🟢 低（签名 + 传 userId） | 🔴 高（token 管理） |
| **多用户支持** | ❌ 不支持 | ✅ 支持 | ✅ 支持 |
| **适用场景** | 一人一 Agent | 一个 Agent 代表多人 | 高安全要求场景 |

---

## 3. Interface — 鉴权链路

> 以下接口设计以**方案 A** 为基准（待确认后调整）。方案 B/C 的差异化接口在备注中标注。

### 3.1 API 调用鉴权（改造现有端点）

**端点**: `ANY /api-server/gateway/api/**`  
**来源**: 改造 [ApiGatewayController.java:58-108]

#### 请求头规范

| Header | 必填 | 说明 | 示例 |
|--------|------|------|------|
| `X-Access-Key` | AKSK 模式必填 | 应用的 Access Key | `AK_PERSONAL_abc123` |
| `X-Timestamp` | AKSK 模式必填 | 请求时间戳 (ms) | `1721116800000` |
| `X-Nonce` | AKSK 模式必填 | 随机字符串 (防重放) | `a1b2c3d4e5f6` |
| `X-Signature` | AKSK 模式必填 | HMAC-SHA256 签名 | `3f2a1b...` |
| `Authorization` | Bearer 模式必填 | 业务应用 Bearer Token | `Bearer eyJhb...` |
| `X-App-Id` | Bearer 模式必填 | 应用 ID (业务应用) | `123456789` |
| `X-Auth-Type` | 可选 | 认证类型 (5=AKSK, 3=IAM) | `5` |

#### 签名算法

复用已有 `SignatureUtil` [SignatureUtil.java:36-107]:

```
签名字符串 = timestamp + "\n" + nonce + "\n" + SHA256(requestBody)
签名 = HMAC-SHA256(secretKey, 签名字符串)
```

**防重放**: 时间戳与服务器时间差 > 5 分钟 → 拒绝 [SignatureUtil.java:50-53]

#### 鉴权处理流程

```
proxyApiRequest(request)
  │
  ├─ [新增] 判断认证模式:
  │   if X-Access-Key 存在 → AKSK 模式
  │   else if Authorization 存在 → Bearer 模式 (现有逻辑)
  │   else → 401
  │
  ├─ [AKSK 模式 - 新增]
  │   1. applicationService.resolveByAksk(ak, timestamp, nonce, signature, body)
  │      └─→ 返回 AppContext {appId, userId, appType, sk}
  │   2. SignatureUtil.verifyAKSKSignature(ak, sk, timestamp, nonce, signature, body)
  │      └─→ 复用 [SignatureUtil.java:36-75]
  │   3. 权限校验 (复用现有 checkPermission)
  │   4. 转发请求，注入 X-User-Id, X-App-Id, X-App-Type 头
  │
  └─ [Bearer 模式 - 现有]
      1. verifyApplication(appId, authType, credential) [现有]
      2. findScopeByPathAndMethod(path, method) [现有]
      3. checkPermission(appId, scope) [现有]
      4. 转发请求 [现有]
```

#### 错误响应

| 场景 | HTTP Status | code | messageZh | messageEn |
|------|-------------|------|-----------|-----------|
| 缺少认证信息 | 401 | `401001` | `缺少认证凭证` | `Missing authentication credentials` |
| AK 无效 | 401 | `401002` | `Access Key 无效` | `Invalid Access Key` |
| 签名验证失败 | 401 | `401003` | `签名验证失败` | `Signature verification failed` |
| 时间戳过期 | 401 | `401004` | `请求时间戳过期` | `Request timestamp expired` |
| AKSK 已过期 | 401 | `401005` | `凭证已过期，请重新生成` | `Credentials expired, please regenerate` |
| AKSK 已吊销 | 401 | `401006` | `凭证已吊销` | `Credentials revoked` |
| 应用非个人类型 | 403 | `403001` | `此认证方式仅支持个人应用` | `This auth method only supports personal apps` |
| 未订阅权限 | 403 | `403002` | `应用未订阅该权限` | `App has not subscribed to this permission` |

### 3.2 凭证生命周期管理（新增端点）

> 以下端点在 **open-server** 中实现（管理面），由前端调用。

#### 3.2.1 生成 AKSK

**端点**: `POST /open-server/service/open/v2/app/credentials`  
**说明**: 为个人应用生成 AK/SK 凭证对

**请求体**:
```json
{
  "appId": 123456789
}
```

**响应体**:
```json
{
  "code": "200",
  "messageZh": "操作成功",
  "messageEn": "Success",
  "data": {
    "ak": "AK_PERSONAL_abc123def456",
    "sk": "sk_live_7f8g9h0j1k2l3m4n5o6p",
    "createdAt": "2026-07-16 10:00:00",
    "expiresAt": "2026-10-14 10:00:00",
    "keyVersion": "v1"
  }
}
```

**业务规则**:
- 每个应用同时只有一对有效 AKSK #ASSUMED
- AK 格式: `AK_PERSONAL_` + 16 位随机字符 #ASSUMED
- SK 格式: `sk_live_` + 24 位随机字符 #ASSUMED
- 默认有效期: 90 天 #ASSUMED（可通过 LookUp 字典配置）
- 生成时写入 `openplatform_app_identity_t` 表

**写入 app_identity 表的字段映射**:

| app_identity 字段 | 值 | 说明 |
|-------------------|-----|------|
| `id` | 雪花 ID | [open-server/.../common/id/IdGeneratorStrategy] |
| `appId` | 请求参数 appId | |
| `ak` | 生成的 AK | |
| `publicKey` | 生成的 SK | 复用现有字段存储 SK [AppIdentity.java:26] |
| `keyVersion` | `v{N}` | 递增版本号 |
| `kitVersion` | `personal` | 标识个人应用凭证 #ASSUMED |
| `status` | `1` (有效) | |
| `tenantId` | 从用户上下文获取 | |

#### 3.2.2 查询 AKSK

**端点**: `GET /open-server/service/open/v2/app/credentials?appId={appId}`  
**说明**: 查询应用的当前有效凭证（SK 脱敏展示）

**响应体**:
```json
{
  "code": "200",
  "data": {
    "ak": "AK_PERSONAL_abc123def456",
    "skMasked": "sk_live_7f8g****3m4n5o6p",
    "createdAt": "2026-07-16 10:00:00",
    "expiresAt": "2026-10-14 10:00:00",
    "keyVersion": "v1",
    "status": 1
  }
}
```

**脱敏规则**: SK 仅显示前 7 位 + `****` + 后 8 位

#### 3.2.3 轮换 AKSK（重新生成）

**端点**: `POST /open-server/service/open/v2/app/credentials/rotate`  
**说明**: 吊销旧 AKSK，生成新 AKSK。旧凭证立即失效。

**请求体**:
```json
{
  "appId": 123456789
}
```

**响应体**: 同 3.2.1

**业务规则**:
- 旧 AKSK 的 `status` 设为 `0` (已吊销)
- 新 AKSK 的 `keyVersion` 递增 (v1 → v2)
- 清除 api-server 中 AK → appId 的 Redis 缓存

#### 3.2.4 吊销 AKSK

**端点**: `DELETE /open-server/service/open/v2/app/credentials?appId={appId}`  
**说明**: 吊销应用的所有有效凭证，不可恢复

**响应体**:
```json
{
  "code": "200",
  "messageZh": "凭证已吊销",
  "messageEn": "Credentials revoked"
}
```

**业务规则**:
- 将 `app_identity` 记录的 `status` 设为 `0`
- 清除 Redis 缓存

---

## 4. Constraints

### 4.1 安全约束

| # | 约束 | 说明 | 来源 |
|---|------|------|------|
| C1 | SK 不记录日志 | 日志中禁止输出 SK 明文 | 安全基线 |
| C2 | SK 传输后不再返回 | 仅生成时返回一次 SK 明文，查询时脱敏 | 安全基线 |
| C3 | 签名防重放 | 5 分钟时间窗口 + nonce 唯一性 | [SignatureUtil.java:47-53] |
| C4 | 凭证强制过期 | 默认 90 天，过期后网关拒绝请求 | #ASSUMED |
| C5 | 单应用单凭证 | 同一时刻一个应用只有一对有效 AKSK | #ASSUMED |

### 4.2 高可用约束

| # | 约束 | 说明 |
|---|------|------|
| C6 | 鉴权链路无外部服务依赖 | AKSK 验证仅查本地 DB + Redis 缓存，不调外部服务 |
| C7 | Redis 不可用时降级 | 缓存未命中直接查 DB，不阻断请求 |
| C8 | 凭证缓存一致性 | 轮换/吊销时主动清除缓存，不依赖 TTL 自然过期 |

### 4.3 性能约束

| # | 约束 | 指标 |
|---|------|------|
| C9 | 鉴权链路额外延迟 | < 5ms (P99)，相比现有业务应用链路 |
| C10 | 凭证查表缓存命中率 | > 95%（AK → AppIdentity 缓存） |
| C11 | 签名计算耗时 | < 1ms (HMAC-SHA256) |

### 4.4 兼容性约束

| # | 约束 | 说明 |
|---|------|------|
| C12 | 业务应用不受影响 | 现有 Bearer Token 鉴权链路完全不变 |
| C13 | 向后兼容请求头 | 新增 `X-Access-Key` 等头不破坏现有 `X-App-Id` + `Authorization` 模式 |
| C14 | Subscription.authType=5 | 个人应用订阅记录 `auth_type` 字段设为 `5` (AKSK) [Subscription.java:30] |

---

## 5. Data

### 5.1 复用表: `openplatform_app_identity_t`

> 来源: [open-server/.../app/entity/AppIdentity.java]

```sql
-- 现有表结构（不改动 DDL）
CREATE TABLE `openplatform_app_identity_t` (
  `id`            bigint       NOT NULL,
  `app_id`        bigint       NOT NULL,
  `public_key`    varchar(256) NULL  COMMENT '存储 SK（个人应用凭证）',
  `private_key`   varchar(256) NULL  COMMENT '预留',
  `key_version`   varchar(20)  NULL  COMMENT '密钥版本号 v1/v2/...',
  `kit_version`   varchar(20)  NULL  COMMENT '标识: personal / business',
  `ak`            varchar(128) NULL  COMMENT 'Access Key',
  `tenant_id`     varchar(64)  NULL,
  `status`        tinyint      NULL  COMMENT '1=有效 0=已吊销',
  `create_by`     varchar(64)  NULL,
  `create_time`   datetime     NULL,
  `last_update_by` varchar(64) NULL,
  `last_update_time` datetime  NULL,
  PRIMARY KEY (`id`)
);
```

**个人应用凭证写入规则:**

| 字段 | 业务应用用法 | 个人应用用法 |
|------|-------------|-------------|
| `ak` | AK | AK (`AK_PERSONAL_` 前缀) |
| `public_key` | 公钥 | SK (HMAC 密钥) |
| `kit_version` | 业务套件版本 | 固定 `personal` |
| `key_version` | 密钥版本 | `v1`, `v2`, ... (轮换递增) |
| `status` | 1=有效 | 1=有效, 0=已吊销 |

### 5.2 新增索引（建议）

```sql
-- api-server 需要通过 AK 反查 appId，需要此索引
ALTER TABLE `openplatform_app_identity_t`
  ADD INDEX `idx_ak_status` (`ak`, `status`);
```

### 5.3 新增过期时间字段（建议）

#ASSUMED — 现有 `app_identity_t` 无 `expires_at` 字段。两种方案：

| 方案 | 说明 | 优劣 |
|------|------|------|
| **A: 复用 app_property 表** | 在 `openplatform_app_p_t` 中存 `propertyName=credential_expires_at` | 零 DDL，但查询多一次 join |
| **B: 新增字段** | `ALTER TABLE app_identity_t ADD expires_at datetime` | 清晰直接，但需 DDL |

### 5.4 Redis 缓存设计

| Key 模式 | Value | TTL | 说明 |
|---------|-------|-----|------|
| `auth:ak:{ak}` | `{appId, sk, appType, userId, keyVersion, status, expiresAt}` (JSON) | 300s | AK → 凭证信息缓存 |
| `auth:ak:{ak}:nonce:{nonce}` | `1` | 300s | Nonce 去重（防重放）|

**缓存清除时机:**
- 凭证轮换 (3.2.3): `DEL auth:ak:{oldAk}`
- 凭证吊销 (3.2.4): `DEL auth:ak:{ak}`
- 凭证过期: 依赖 TTL 自然过期 + 网关二次校验 DB

### 5.5 api-server 新增 Mapper

> api-server 当前无 `AppIdentityMapper`，需新增。

```java
// api-server/.../common/mapper/AppIdentityMapper.java (新增)
@Mapper
public interface AppIdentityMapper {
    AppIdentity selectByAk(@Param("ak") String ak);
}
```

```xml
<!-- api-server/src/main/resources/mapper/AppIdentityMapper.xml (新增) -->
<select id="selectByAk" resultType="com.xxx.api.common.entity.AppIdentity">
    SELECT id, app_id, ak, public_key AS sk, key_version, kit_version, status, tenant_id
    FROM openplatform_app_identity_t
    WHERE ak = #{ak} AND status = 1
    LIMIT 1
</select>
```

---

## 6. Test Cases

### 6.1 鉴权链路测试

| # | 用例 | 输入 | 预期结果 |
|---|------|------|---------|
| T1 | AKSK 签名正确，权限已订阅 | 有效 AK + 正确签名 + 已订阅 scope | 200, 转发成功 |
| T2 | AK 不存在 | 无效 AK | 401, `401002` |
| T3 | 签名错误 | 有效 AK + 错误签名 | 401, `401003` |
| T4 | 时间戳过期 (>5min) | 有效 AK + 过期 timestamp | 401, `401004` |
| T5 | 凭证已过期 (>90天) | 有效 AK + 过期 expiresAt | 401, `401005` |
| T6 | 凭证已吊销 | 已吊销 AK | 401, `401006` |
| T7 | 未订阅权限 | 有效 AK + 未订阅 scope | 403, `403002` |
| T8 | 订阅待审批 | 有效 AK + subscription.status=0 | 403, "订阅待审批" |
| T9 | 业务应用使用 AKSK 模式 | 业务应用 AK + AKSK 签名 | 403, `403001` (仅个人应用) |
| T10 | Nonce 重放 | 相同 nonce 第二次请求 | 401, `401003` (nonce 重复) |
| T11 | Bearer Token 模式不受影响 | 业务应用 Bearer Token | 200 (现有逻辑不变) |
| T12 | 无认证信息 | 无任何认证头 | 401, `401001` |

### 6.2 凭证生命周期测试

| # | 用例 | 输入 | 预期结果 |
|---|------|------|---------|
| T20 | 生成 AKSK | appId (个人应用) | 200, 返回 AK/SK/expiresAt |
| T21 | 重复生成 AKSK | 已有有效 AKSK 的 appId | 409, "应用已有有效凭证" |
| T22 | 查询 AKSK | appId | 200, SK 脱敏展示 |
| T23 | 轮换 AKSK | appId (已有 v1 凭证) | 200, 新 AKSK, keyVersion=v2, 旧凭证 status=0 |
| T24 | 轮换后立即使用旧 AK | 旧 AK + 正确签名 | 401, `401006` |
| T25 | 轮换后使用新 AK | 新 AK + 正确签名 | 200, 正常通过 |
| T26 | 吊销 AKSK | appId | 200, status=0 |
| T27 | 吊销后使用 AK | 已吊销 AK | 401, `401006` |
| T28 | 业务应用生成 AKSK | appId (业务应用) | 403, "仅个人应用支持" |

### 6.3 高可用测试

| # | 用例 | 条件 | 预期结果 |
|---|------|------|---------|
| T30 | Redis 不可用 | Redis 连接断开 | 降级查 DB，鉴权仍通过（延迟增加） |
| T31 | DB 不可用 | MySQL 连接断开 | 503, 鉴权失败（无法验证 AK） |
| T32 | 缓存穿透 | 大量无效 AK 请求 | 缓存空值 (TTL=60s) 防止 DB 压力 |

---

## 7. 待确认项

| # | 问题 | 影响范围 | 建议 |
|---|------|---------|------|
| Q1 | **身份解析方案选择 A/B/C** | 鉴权链路核心设计 | 建议方案 A（最简、一人一 Agent 场景匹配） |
| Q2 | **过期时间存储方案** (5.3 节 A/B) | DDL 改动范围 | 建议方案 B（新增字段，清晰直接） |
| Q3 | **AKSK 默认有效期** | 凭证生命周期 | 建议 90 天，可配置 |
| Q4 | **Nonce 防重放是否启用** | 安全等级 vs 性能 | 建议启用，Redis SET NX，TTL=300s |
| Q5 | **api-server 如何访问 app_identity 表** | 部署架构 | 当前 api-server 和 open-server 共享 MySQL，直接新增 Mapper 即可 #ASSUMED |

---

## 附录 A: 代码变更影响矩阵

| 服务 | 文件 | 变更类型 | 说明 |
|------|------|---------|------|
| **api-server** | `ApiGatewayController.java` | 修改 | 增加 AKSK 认证分支 |
| **api-server** | `ApiGatewayService.java` | 修改 | 增加 AKSK 验签 + 身份解析 |
| **api-server** | `ApplicationService.java` | 修改 | 新增 `resolveByAksk()` 方法 |
| **api-server** | `ApplicationServiceMockImpl.java` | 修改 | Mock 实现 AKSK 解析 |
| **api-server** | `AppIdentityMapper.java` | 新增 | `selectByAk()` |
| **api-server** | `AppIdentityMapper.xml` | 新增 | SQL 映射 |
| **api-server** | `AppIdentity.java` | 新增 | 实体类（从 open-server 复制简化版） |
| **api-server** | `AppContext.java` | 新增 | 鉴权上下文 VO |
| **api-server** | `application.yml` | 修改 | 新增缓存 TTL 等配置 |
| **open-server** | `AppController.java` | 修改 | 新增凭证管理端点 |
| **open-server** | `AppService.java` | 修改 | 新增凭证生成/轮换/吊销方法 |
| **open-server** | `AppServiceImpl.java` | 修改 | 凭证管理实现 |
| **open-server** | `AppIdentityMapper.java` | 修改 | 新增 `updateStatus()`, `selectByAk()` |
| **open-server** | `AppIdentityMapper.xml` | 修改 | 新增 SQL |
| **open-server** | `V4__personal_credential_support.sql` | 新增 | DDL: idx_ak_status, expires_at |
