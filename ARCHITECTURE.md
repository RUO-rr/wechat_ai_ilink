# 微信机器人多 Bot 系统 —— 架构演进文档

## 项目定位

基于 Spring Boot + DeepSeek V4 Flash 的微信智能助手，支持多用户独立扫码登录、自然语言对话、Function Calling 工具调用、Word 文档生成、企业信息查询。

---

## 一、架构演进路线

### v1.0 → v2.0 多 Bot 架构

```
v1.0（单 Bot 硬编码）                    v2.0（多 Bot 动态架构）
─────────────────────────               ─────────────────────────
MainController.run()                    BotManager (生命周期管理器)
  └─ new ILinkClient()                    ├─ Bot A (独立线程池)
     └─ 全局单例                          ├─ Bot B (独立线程池)
                                          └─ Bot C (独立线程池)
                                         每个 Bot 有自己的 client/state/qr/executor
```

### v2.0 → v2.1 身份模型重构

```
v2.0（伪造身份链路）                     v2.1（SDK 信任根链路）
─────────────────────────               ─────────────────────────
POST /bot/create?userId=wangyangxu      POST /bot/create?label=办公机
  → userId 是用户编造的假 ID               → systemBotId 随机生成
  → BotInstance.ownerUserId = 假 ID       → wechatUserId = null（待 SDK 注入）
  → LoginContext.userId 被丢弃 ❌          → onLoginSuccess 注入真实微信 ID ✅
  → msg.from_user_id 无法关联 ❌           → msg.from_user_id 自动对齐 ✅
```

---

### v2.1 → v2.2 数据层演进

```
v2.1（SQLite + JVM LRU）                 v2.2（MySQL + Redis）
─────────────────────────               ─────────────────────────
SQLite 单文件数据库                        MySQL 8 (InnoDB / utf8mb4)
  ├── 单机单写者（Hikari pool=1）          ├── 连接池 10，支持真实部署
  └── 消息双写：内存 LRU + DB              └── 消息双写：Redis(缓存) + MySQL(持久化)

ConversationHistory JVM LRU               ConversationHistory Redis
  ├── LinkedHashMap accessOrder           ├── key = chat:history:{botId}:{userId}
  ├── max=100 sessions / TTL 30min        ├── TTL 30min（Redis EXPIRE）
  └── 重启即失                            └── 重启不丢，跨实例共享

UserVoiceState 内存 Map                   UserVoiceState Redis String
```

**迁移动机**：SQLite 适合单机单写者，但无法支撑多实例部署与容器化；
MySQL 承担持久化、Redis 承担缓存，配合 `docker-compose.yml` 实现可复现部署。

### v2.2 → v2.3 Bot 运行时健壮性（被抢占重登 + 会话降级）

```
问题：微信在别处登录后，本 bot 无法重新扫码
  ├─ 免扫码恢复优先：旧 token 在 SDK 本地 isLoggedIn()=true → 直接 ONLINE，不生成二维码
  ├─ 管理页「刷新二维码」复用了同一恢复逻辑 → 永远等不到新二维码
  └─ 断线后无「会话失效 → 重新扫码」路径，autoReconnect 用同一个死 token 无限重试

决策：
  ├─ 强制重新扫码：loginBotAsync(botId, forceNewQr)，force 时跳过 LoginContext 注入
  ├─ 登录串行化：per-bot ReentrantLock，旧 client.close + 新 client.build/login 同一锁保护区
  ├─ 回调按 client 实例过滤：boundClient == bot.getClient() 才处理，杜绝旧 client 残留回调污染
  ├─ 并发保护：AtomicBoolean CAS，重复触发返回「正在登录中」
  └─ 会话降级：OnDisconnectListener.onReconnectFailed / 心跳连续失败 → DISCONNECTED
     → 免扫码恢复一次 → 失败进入二维码流程 + alertSessionLost

明确不做：30 秒观察期 + 「有无消息」判定 token 有效性 —— 理由见决策记录 D-05
```

### v2.3 → v2.4 Context Manager（长期记忆）

```
需求澄清：「用 RAG 压缩聊天记录」是概念错位 —— 记忆压缩是上下文管理问题，不是检索问题；
RAG 的正确落点是文档知识库（见 Roadmap，与长期记忆共用检索基建）。

微信场景约束：单次会话短，40 条消息摘要阈值永远不触发 → 滚动摘要改为每 10 轮触发。

决策：
  ├─ 记忆三轨隔离：fact/preference（LLM 提取 + supersede）/
  │    summary（每 10 轮滚动）/ note（remember 工具手动写，永不 supersede）
  ├─ 冲突解决：dimension 归一化 + recency-wins supersede + supersedes_id 审计链（读路径 O(1)）
  ├─ 记忆只进 MySQL：不做「DB + 磁盘双写」——伪需求，理由见决策记录 D-07
  ├─ delete 一律软删除（status=deleted）保留审计轨迹
  ├─ 读路径分槽位注入：摘要槽 1 / 记忆槽 ≤5 / 笔记槽 ≤3，互不抢配额
  ├─ 异步执行 + feature flag 采样（extraction-enabled / sample-rate）控制成本
  └─ agent_memory 无 bot_id：记忆跟随微信用户而非 Bot 实例（换 bot 抢占场景的必然结论）
```

### v2.4 → v2.5 消息性能与可观测性

```
关键发现（反编译 SDK 字节码）：「心跳」= 消息轮询
  └─ HeartbeatService.scheduleWithFixedDelay(30s) → healthChecker.check()
       = pollAndDispatchMessages() → UpdateService.poll() → 同步分发 onMessages
  ├─ 消息最坏等一个轮询周期（30s）才开始被拉取
  ├─ onMessages 同步分发 → LLM 处理阻塞轮询线程 → scheduleWithFixedDelay 顺延 → 恶性循环
  └─ 30s 间隔 + 35s readTimeout 并存 → 有效轮询周期可能 30~65s

修复（1+2+4）：
  ├─ ① heartbeatIntervalMs 30s → 3s（最坏投递等待降到秒级）
  ├─ ② per-bot 单线程消息执行器（有界队列 100，满则丢弃 + WARN）——处理与拉取解耦
  ├─ ③ 心跳失败阈值 3 → 10（间隔缩短的连带：10 次 ≈ 30s 失败窗口，防抖动误伤）
  └─ ④ 意图检测并入 FC：标记协议 + 强信号硬路由兜底 + 失败先提示原因再走文本回复

度量闭环（v2.5）：
  ├─ MetricsService：链路计时（traceId 经 MDC 跨线程传到记忆 worker）+
  │    延迟分位（avg/p50/p95）+ FC 轮次 + LLM 耗时 + 队列丢弃 + 会话丢失计数
  └─ 管理 API（X-API-Token 鉴权）：GET /api/health、GET /api/metrics —— 只读，不暴露写接口
```

---

## 二、核心技术决策与技术亮点

### 2.1 Function Calling 工具系统 —— 策略模式 + 动态装配

**设计原则**：对扩展开放，对修改关闭。

```
tool/
├── ToolDefinition.java        ← 核心接口（name / domain / definition / execute）
├── ToolRegistry.java          ← Auto-discovery（Spring 注入所有 @Component 实现）
├── ToolRouter.java            ← 领域路由（信号词匹配 → 工具子集筛选 + 降级模式）
└── impl/
    ├── WeatherTool.java
    ├── WordDocumentTool.java  ← 聚合路由器（内部收集 7 个 WordOperation 策略）
    ├── ExcelTool.java
    ├── GenerateImageTool.java
    ├── SearchCompanyInfoTool.java
    ├── SearchIndustryNewsTool.java
    ├── WordToPdfTool.java（LibreOffice）
    ├── ResumeTool.java（内部收集 4 个 ResumeOperation 策略）
    ├── RememberTool.java（长期笔记，v2.4）
    └── word/                   ← Word 域策略模式
        ├── WordOperation.java  ← 策略接口
        ├── CreateDocumentOp.java
        ├── AddImageOp.java
        ├── ModifyTitleOp.java
        ├── SetIndentOp.java
        ├── AddHeadingOp.java
        ├── FormatHeadingOp.java
        ├── SendDocumentOp.java
        └── WordOpHelper.java
```

**技术亮点**：
- `WordDocumentTool` 作为瘦壳路由器，构造函数注入 `List<WordOperation>`，Spring 自动收集所有策略实现。新增操作只需新建一个 `@Component` 类，零侵入已有代码。
- ToolRegistry 类似的模式：`ToolRegistry(List<ToolDefinition>)` 构造注入，启动日志打印所有已注册工具。

### 2.2 迭代式 Function Calling 循环

**设计来源**：参考 LangChain AgentExecutor（max_iterations）+ Dify Workflow（始终带 tools）+ Python 伪代码范例。

```java
// FunctionCallingOrchestrator: 核心循环（简化版）
for (int step = 0; step < MAX_STEPS; step++) {
    response = llmApi.call(messages, TOOLS);  // 每轮都带 tools
    if (no tool_calls) break;                  // LLM 决定结束
    messages.add(assistant_with_tool_calls);
    for (toolCall : tool_calls) {
        result = toolRegistry.execute(name, args);
        messages.add(tool_result);             // 追加 tool 结果，下一轮能看见
    }
}
```

**关键决策**：所有轮次都带 `tools` 参数，不摘掉。LLM 看到上一轮的 tool 结果后，自动判断是否需要继续调工具。配合 `maxSteps=15` 和循环保护机制（同一工具+参数连续成功 2 次 = break）防止死循环。

### 2.3 上下文持久化与跨轮记忆

**问题**：FC 循环内部的 tool 消息（含 `file_path`）只存在于临时 `messages` 列表，不进 `ConversationHistory`。下一轮对话开始时 LLM 不知道上一轮操作的文件路径。

**解决方案**：
- `ConversationHistory` 从 `Map<String, String>` 升级为 `Map<String, Object>`，支持 `tool` 和 `assistant(tool_calls)` 的富结构消息
- FC 循环结束后调用 `persistToolMessages()` 将 tool 消息写入历史
- 跨轮对话时 LLM 能读到 `file_path`，正确执行后续修改

### 2.4 领域路由 —— 降低 LLM 选择负担

**问题**：工具增多后每次请求注入全部 5+ 个 tool schema，LLM Token 消耗大且容易选错。

**解决方案**：信号词匹配 → 领域工具子集

| 用户消息 | 领域 | 注入工具数 |
|---------|------|-----------|
| "北京天气" | weather | 1 |
| "生成Word文档" | document | 1 |
| "查小米公司" | company | 2 |
| "你好" | 无匹配 → 降级 | 全部 + 兜底 prompt |

**降级模式**：复合意图/无匹配时注入全量工具 + 自主决策 prompt，保证覆盖率。

### 2.5 多 Bot 动态管理架构

```
POST /bot/create?label=办公机
  → BotManager.createBot(label)
    → 生成随机 systemBotId（bot_xxxxxxxx）
    → BotInstance (systemBotId, label, executor, state=UNINITIALIZED)
  → BotManager.loginBotAsync(botId)
    → 独立线程池执行 doLogin()
      → ILinkClient.builder()
          .loginContext(savedCtx)    ← 尝试免扫码恢复（如果有历史 token）
          .onLogin().onMessage().build()
      → 若 token 有效 → 直接 ONLINE（免扫码）
      → 若 token 过期 → 生成二维码 (QR_READY)
      → 等待扫码 (LOGGING_IN)
      → onLoginSuccess 注入真实身份：
          wechatUserId = ctx.getUserId()   ← SDK 返回的扫码者微信 ID
          wechatBotId  = ctx.getBotId()    ← SDK 返回的 Bot ID
          botToken     = ctx.getBotToken()
      → 登录成功 (ONLINE) → upsert bot_registry（写入真实身份）→ audit
      → 超时 (QR_EXPIRED) → tryReconnect(3)
      → 失败 (ERROR) → alert

GET /bot/{botId}/qr      → 前端展示二维码
GET /bots                → 所有 Bot 状态列表（含 wechatUserId/label）
DELETE /bot/{botId}      → shutdown + delete registry
GET /bot/health/{botId}  → 健康检查（状态/连接/运行时长）
GET /bot/health          → 全局概览（在线数/错误数/上限）
```

**关键设计**：
- `BotInstance` 持有独立的 `ExecutorService(core=2, max=4)`，一个 Bot 的慢请求不阻塞其他 Bot
- 消息处理有独立的单线程执行器 `msg-{botId}`（v2.4 起），与登录线程池、SDK 轮询线程解耦
- 闭包注入 `botId`：`BotMessageListener` 构造函数接收 `BotInstance`，回调时通过 `BiConsumer<String, WeixinMessage>` 传入 `botId`
- `BotContext`（ThreadLocal）在消息入口 set，`finally` 块 clear
- v2.3 起 `loginBotAsync(botId, forceNewQr)` 支持强制重扫，登录流程串行化，详见 2.10

### 2.6 双 ID 身份模型 ★

**设计原则**：不以系统自行声明的任何字段作为用户身份依据，唯一信任根是微信 iLink 平台的认证结果。

```
┌─────────────────────────────────────────────────┐
│                  BotInstance                     │
│  ┌───────────────────────────────────────────┐  │
│  │ systemBotId:  "bot_a1b2c3d4"              │  │  ← 系统内部句柄（随机生成）
│  │ label:        "办公机"（可选）              │  │  ← 管理界面展示用
│  │ wechatUserId: "wxid_abc123"               │  │  ← SDK LoginContext.userId ★
│  │ wechatBotId:  "ilink_xxx"                 │  │  ← SDK LoginContext.botId  ★
│  │ botToken:     "token_xxx"                 │  │  ← SDK 鉴权令牌
│  │ baseUrl:      "https://..."               │  │  ← SDK 服务端点
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘

systemBotId    → 内部 Map key、REST API path、线程命名
wechatUserId   → 会话隔离 key、历史记录 key、Bot-User 绑定 ★
wechatBotId    → 日志、审计、与微信平台对接
```

**身份注入时机**：

```
SDK 扫码成功
  → onLoginSuccess(LoginContext ctx)
    → bot.setWechatIdentity(ctx.getUserId(), ctx.getBotId(), ctx.getBotToken(), ctx.getBaseUrl())
    → botRegistryMapper.upsert(botId, label, wechatUserId, wechatBotId, botToken, baseUrl)
```

**身份对齐验证**：

```
消息到达: msg.from_user_id = "wxid_abc123"        ← SDK 返回的发信人微信 ID
Bot 身份: bot.wechatUserId      = "wxid_abc123"   ← 扫码者微信 ID
→ 两者天然对齐，都是微信 ID 体系 ✅
```

**身份不匹配处理（宽松模式）**：
- 重启后重新扫码 → 比较新旧 `wechatUserId`
- 不同 → 接受新身份 + 更新 DB + 触发 `BOT_ALERT` 告警
- 相同 → 正常恢复

**为什么不用系统自行声明的 userId**：
- 调用方可以填写任意值，无法验证
- 与 `msg.from_user_id` 不属于同一 ID 体系，无法关联
- 重启恢复时无意义

### 2.7 数据隔离与持久化（v2.2：SQLite → MySQL + Redis）

```
MySQL 8 (InnoDB / utf8mb4)
├── chat_message (bot_id + user_id 联合索引)
│   └── 所有消息持久化，bot_id 隔离，user_id 为真实微信 ID
└── bot_registry (bot_id PK)
    ├── label          ← 展示标签
    ├── wechat_user_id ← SDK 真实微信 ID（索引）
    ├── wechat_bot_id  ← SDK 分配的 Bot ID
    ├── bot_token      ← 用于免扫码恢复
    └── base_url       ← SDK 服务端点
```

**ConversationHistory 缓存架构（v2.2：JVM LRU → Redis）**：
```
Redis (String + JSON, Cache-Aside)
  ├── 缓存 key = chat:history:{botId}:{userId}（复合键，多 Bot 隔离）
  ├── TTL 30 分钟自动过期 → 从 MySQL 重载
  ├── 命中即返回；未命中 → 加载 MySQL → 写回 Redis（SETEX）
  ├── 每会话本地锁，防止并发读改写丢失更新
  └── 双写：Redis + MySQL（Redis 失败降级走 DB；DB 失败降级仅 Redis，不阻断对话）
```

### 2.8 稳定性保障

| 机制 | 实现 |
|------|------|
| 启动恢复 | `@PostConstruct recoverFromDb()` → 读 `bot_registry`（含真实身份）→ 构建 `LoginContext` 免扫码恢复 → token 过期则重新扫码 |
| 掉线重连 | 超时 → 重试 3 次 × 5 秒间隔 → 全失败 → ERROR + 告警 |
| 循环保护 | 同一工具+参数连续成功 2 次 → 强制终止 |
| 身份变更检测 | `onLoginSuccess` 比对历史 `wechatUserId`，不匹配时告警 + 更新 |
| 优雅关闭 | `@PreDestroy` → `CompletableFuture.allOf()` 并行关闭，5s 超时 |
| 路由验证 | `IintService.getClient()` 断言 `ctx.botId == 请求 botId` |
| 缓存隔离 | Redis key 为 `chat:history:{botId}:{userId}` 复合键 |
| ThreadLocal 清理 | `BotContext.clear()` in `finally` |
| 审计告警 | `BOT_AUDIT` / `BOT_ALERT` 独立 Logger，支持日志采集系统过滤 |

### 2.9 Watcher 后处理

FC 循环结束后检查 LLM 是否遗漏关键工具调用。company 领域的深度分析场景下，如果 `search_company_info` 被调了但 `search_industry_news` 没被调，自动在回复末尾追加引导提示。这是"架构层软兜底"——不在 prompt 里约束 LLM，而在代码层检测遗漏。

### 2.10 会话降级与强制重登（v2.3）

**问题**：微信账号在别处登录后，`isLoggedIn()` 仍可能本地为 true（token 是否有效由 SDK 本地判断），
免扫码恢复直接上线、不生成二维码；管理页刷新又复用同一逻辑，用户永远无法重新扫码。

**方案**（详见演进 v2.3 + 决策记录 D-05/D-06）：
- 登录统一入口 `loginBotAsync(botId, forceNewQr)`：创建 / 启动恢复 / 强制刷新 / 断线降级共用，
  `force=true` 跳过 LoginContext，强制走二维码
- per-bot `ReentrantLock`：旧 client close + 新 client build/login 原子化；`AtomicBoolean` CAS 防重复触发
- 四个监听器（login / message / disconnect / heartbeat）都绑定自身 client 实例，回调首行
  `boundClient == bot.getClient()` 过滤——旧 client 关闭瞬间的残留回调被丢弃
- 会话降级只信 SDK 底层信号：`onReconnectFailed`（自动重连彻底失败）或心跳连续失败
  → DISCONNECTED → 免扫码恢复一次 → 失败进二维码流程 + `alertSessionLost`

### 2.11 长期记忆 Context Manager（v2.4）

**存储模型**（`agent_memory`，按 `user_id` 维度，无 bot_id）：

```sql
id BIGINT PK, user_id, memory_type(fact/preference/summary/note),
dimension, content, source_message_id(溯源), status(active/superseded/deleted),
supersedes_id(审计链), created_at, updated_at
```

**冲突解决（recency wins）**：写路径按 `dimension` 归一化（trim + lowercase）后比对最新 active，
新值 supersede 旧值（旧值置 superseded，新值挂 supersedes_id）；读路径只取 active（O(1)），
审计时按链反向回溯。示例："喜欢简洁"→"喜欢详细带例子"，读路径只见后者，行为确定。

**三轨隔离与读路径槽位**：

| 轨 | 写入方 | dimension | 冲突 | 读路径 |
|---|--------|-----------|------|--------|
| fact/preference | 每轮 LLM 提取（采样） | 自由命名 | supersede | 记忆槽 ≤5 |
| summary | 每 10 轮滚动 | history | supersede | 摘要槽 1 |
| note | remember 工具 | user_note | 永不，手动软删除 | 笔记槽 ≤3 |

**健壮性**：LLM 输出整包解析失败 → 跳过本轮下轮重试；单条非法 → 丢弃该条留好条目（WARN 含原始片段）；
提取/摘要均异步执行且带 feature flag 采样，不阻塞回复、可控制成本。

### 2.12 消息投递与性能（v2.4 → v2.5）

**机制发现（反编译）**：SDK 无 WebSocket 推送，消息投递 = 心跳调度器内的 `pollAndDispatchMessages()`
（scheduleWithFixedDelay）。因此缩短 `heartbeatIntervalMs` 即缩短投递最坏等待，这是 30s → 3s 的依据。

**异步化**：`onMessages` 只做入队（per-bot 单线程 `msg-{botId}`，有界队列 100，满则丢弃 + WARN + 指标），
轮询线程立即返回；处理与拉取解耦，`scheduleWithFixedDelay` 不再被 LLM 耗时顺延。

**意图协议**（省一次 LLM 往返）：意图判断并入 FC system prompt ——
语音 `[VOICE]` / 切换音色 `[VOICE_SWITCH:名]` / 画图调 `generate_image`；
独立意图检测退化为纯强信号关键词硬路由（零 LLM），弱信号一律交 FC。
失败兜底：硬路由服务失败 → 先提示"因为[原因]暂时不能提供服务" → 再走文本回复。

### 2.13 可观测性与管理面（v2.5）

- `MetricsService`：ThreadLocal 在消息线程内聚合单条统计（FC 轮次 / LLM 调用），
  `endMessage` 聚合全局并返回本条结果；全局计数器 + 最近 200 条延迟环形缓冲（avg/p50/p95）
- TraceId 跨线程：消息执行器线程设 BotContext/MDC → 记忆异步任务显式接收 traceId 并在 worker 线程
  重设 MDC，整条链路（含记忆任务）可用同一 traceId 串日志
- 管理 API：`X-API-Token` 拦截器（常量时间比较）保护 `/api/**`；只暴露只读的
  `GET /api/health` 与 `GET /api/metrics`，刻意不暴露任何修改记忆/发送消息的接口
- 测试与 CI：FC 编排器 / ConversationHistory（Redis 层）/ BotManager / ToolRouter /
  Memory 系列共 41 个单测；GitHub Actions 以服务容器起 MySQL 8.4 + Redis 7.4 跑 `mvn test`

---

## 三、代码质量改进

| 改进 | 说明 |
|------|------|
| 职责拆分 | ChatTextService 从 360 行 → 110 行，提取 IntentDetectionService / SpeechTextGenerationService / FunctionCallingOrchestrator |
| 共享 Bean | RestTemplateConfig 统一超时配置（连接 5s / 读取 60s），消除 6 处 `new RestTemplate()` |
| LlmClient | 封装 OpenAI 兼容 API 的 URL 构建/Header/响应解析，消除 15+ 处重复代码 |
| 日志安全 | 用户数据从 INFO → DEBUG，userId 脱敏，响应体不落盘 |
| 线程安全 | ConversationHistory 每会话本地锁 + Redis Cache-Aside（v2.2 起），多 Bot 并发互不阻塞 |
| 错误恢复 | DB 写入失败降级为仅 Redis/内存；Redis 失败降级走 DB；临时文件删除失败打 WARN |
| 身份模型 | 移除伪造 userId 链路，以 SDK LoginContext 为唯一信任根 |
| 消息异步化 | onMessages 只入队，per-bot 单线程执行器处理，轮询线程不被 LLM 阻塞（v2.4） |
| 可观测性 | 链路计时 traceId 跨线程 + MetricsService（avg/p50/p95、FC/LLM/队列/会话指标） |
| 管理面收敛 | /api/** 走 X-API-Token 鉴权，只读端点 /api/health、/api/metrics |

---

## 四、关键指标

| 指标 | 数值 |
|------|------|
| 支持 Bot 数 | ≤ 10（可配置，`bot.max-bots`） |
| 工具总数 | 9 个 ToolDefinition 实现（weather/word/excel/pdf/resume/image/company/news/remember） |
| Word 域操作数 | 7 个 WordOperation 策略 |
| Resume 域操作数 | 4 个 ResumeOperation 策略 |
| FC 循环上限 | 15 步 + 2 次重复保护 |
| systemBotId 碰撞概率 | 36^8 ≈ 1/2.8万亿 |
| 缓存 TTL | 30 分钟 |
| 缓存隔离 | Redis key `chat:history:{botId}:{userId}` 复合键 |
| 数据层 | MySQL 8（InnoDB / utf8mb4）+ Redis 7（缓存） |
| 消息轮询/心跳 | 3 秒（heartbeatIntervalMs=3000） |
| 消息执行器 | per-bot 单线程，有界队列 100，满则丢弃 + WARN |
| 记忆注入槽位 | 摘要 1 / 记忆 ≤5 / 笔记 ≤3 |
| 记忆采样 | extraction-enabled + sample-rate（默认 1.0，可降 0.5） |
| 单测 | 41 个（FC 编排 / ConversationHistory / BotManager / ToolRouter / Memory / 意图路由） |
| CI | GitHub Actions：MySQL 8.4 + Redis 7.4 服务容器 + `mvn test` |
| 重启恢复 | 全自动（bot_registry 持久化 LoginContext + 免扫码恢复） |
| 编译结果 | 零 ERROR |

---

## 五、决策记录（Why / Why Not）

> 按时间顺序收录项目演进中的关键取舍。每条记录回答两个问题：
> **为什么要**（收益与证据）与**为什么不要替代方案**（被否方案的成本/风险/场景缺失）。

### D-01 Git 工程化与数据卫生
- **决策**：单次"作业"提交 → 语义化提交历史；`.gitignore` 排除 `data/`（聊天记录 + 个人简历）、
  `voice_cache/`、`*.db`、`dump.rdb`；删除根目录死文件（`SpeechService`、`callDrawApi.txt`、vendored SDK 类）。
- **为什么**：面试仓库的第一印象是 git 历史与"打开即构建"；`data/` 含真实聊天与简历，`git add -A`
  一次即泄漏；死文件是构建链与代码库的噪音。
- **为什么不是**"历史重写成一笔漂亮提交"：重写有数据风险且不诚实；真正有价值的是从当下开始的分支纪律。

### D-02 命名与开源资源归属
- **决策**：`com.example:wea_forecast` → `io.github.wangyangxu:ai-ilink`（Maven 坐标 + Java 包名）；
  `resume-builder/` 目录标注来源（openai-resume-builder，MIT）并附许可证文本。
- **为什么**：模板坐标一眼即"作业"；拷入 MIT 资源不署名既违反协议又会让面试官质疑原创性——署名反而
  把叙事变成"引入开源方法论 + 工程化集成"。
- **为什么不是**全量 vendor SDK：wechat-ilink-sdk 走 GitHub Packages 私有源，全量 vendor 需先确认 license
  且改动量大；取舍是保留依赖 + README 写明凭据配置，并删除两个冗余的半拷贝类。

### D-03 SQLite → MySQL + Redis
- **决策**：chat/bot 落 MySQL（InnoDB/utf8mb4），会话缓存换 Redis（Cache-Aside，TTL 30min）；补 docker-compose。
- **为什么**：SQLite 单写者模型（Hikari pool=1）无法支撑容器化/多实例；utf8mb4 是微信 emoji 的硬要求；
  Redis 让缓存可跨实例、重启不丢，且为多 bot 会话隔离提供统一 key 空间。
- **为什么不是**老数据迁移：个人项目历史数据价值低，重头开始 + 备份更省；迁移的三处 SQLite 语法坑
  （`AUTOINCREMENT`/`ON CONFLICT`/`IN + LIMIT`）是真实踩过的，文档保留即为教训记录。
- **踩坑备注**：JDBC URL 用 `characterEncoding=utf8mb4` 会直接报 `Unsupported character encoding`——
  `utf8mb4` 是服务端字符集名，Java 侧要用 `UTF-8` + `connectionCollation`。

### D-04 管理页删除"用户标识"
- **决策**：创建 Bot 只保留可选 `label`，删掉必填的"用户标识"输入。
- **为什么**：该输入是 v1.0 伪造身份链路的残留——后端早已忽略 `userId`，前端却强制必填，
  既误导管理员又违背 v2.1"身份信任根=SDK 扫码结果"的决策。
- **为什么不是**保留并让它生效：调用方填的 ID 无法验证、与 `msg.from_user_id` 不同体系、重启无意义——
  这正是 v2.1 已经否决过的方案，前端只是没跟上。

### D-05 会话丢失检测：SDK 回调优先（否决启发式）
- **决策**：会话降级只依赖 SDK 底层回调——`OnDisconnectListener.onDisconnect/onReconnectFailed`、
  `OnHeartbeatListener.onHeartbeatFailure`；恢复路径为 DISCONNECTED → 免扫码恢复 → 失败进二维码 + 告警。
- **为什么**：被踢/断线/认证失败是 SDK 连接的**事实状态**，回调是可靠信号源；
  不依赖业务层的"消息流量"，避免行为随机。
- **为什么不要**"30 秒观察期 + 有无消息判定 token 有效性"：
  ① 一次性定时器覆盖不了"登录 5 分钟后被服务器踢掉"——观察期早已销毁；
  ② 用消息流量判活会频繁误触发（用户本来就没发消息 ≠ token 死了），体验灾难。已实测反编译确认
  SDK 提供的正是 onDisconnect/onHeartbeat 一族接口，方案可行而非猜测。

### D-06 强制重扫与登录串行化
- **决策**：`loginBotAsync(botId, forceNewQr)` 作为唯一登录入口；force 时跳过 LoginContext 直接出二维码；
  per-bot `ReentrantLock` 把"旧 client.close + 新 client.build/login"做成原子区段；
  `AtomicBoolean` CAS 防重复触发；四个监听器按 client 实例过滤回调。
- **为什么**：被抢占后旧 token 仍可能 `isLoggedIn()=true`，只有"不信任旧身份、强制重扫"才是逃生门；
  直接 close 旧 client 会与其回调线程竞态，必须串行化 + 回调归属过滤。
- **为什么不是**每次登录都强制扫码：免扫码恢复是启动与日常的体验核心，破坏它等于自毁；
  正确形态是"默认恢复 + 手动强制 + 自动降级"三层。

### D-07 记忆存储：只进 MySQL（否决 DB+磁盘双写）
- **决策**：`agent_memory` 表是唯一事实源；`remember` 工具纯 DB 读写；磁盘仅承载可重建的工作产物。
- **为什么**：容器/云函数/K8s 的 `data/` 是临时存储——"文件系统卸载"隐含"文件系统持久"假设；
  把记忆落磁盘等于默认它会被重启清空。
- **为什么不做**"DB 成功 + 磁盘尽力同步"：这是把两个持久化层强绑成原子操作的伪需求——
  返回成功但磁盘没写 → agent 以为记住了却读不到；返回失败 → agent 重试导致 DB 重复。
  失败语义矛盾无法两全，正确做法是砍掉第二层，只保留单一事实源。

### D-08 记忆冲突解决与软删除（否决 append-only）
- **决策**：写入前 `dimension = trim().toLowerCase(Locale.ENGLISH)` 归一化；
  同维度冲突用 recency-wins supersede（旧记录置 superseded，新记录挂 supersedes_id）；
  读路径只取 active（O(1)），审计按链反向回溯；delete 一律软删除。
- **为什么**：append-only 是 bug——"今天喜欢简洁、明天喜欢详细带例子"两条矛盾偏好同时 active，
  agent 行为随机。冲突解决后读路径只见最新值，行为确定，且 supersedes 链完整保留偏好演变史。
- **为什么软删除**：物理删除会毁掉"记忆可审查"——`source_message_id` 溯源 + 状态链才是记忆系统
  区别于普通缓存的证据；个人项目成本几乎为零。

### D-09 记忆范围与摘要触发（场景驱动的收敛）
- **决策**：`agent_memory` **不设 bot_id**（记忆跟随微信用户）；摘要每 **10 轮**滚动而非 40 条触发；
  LLM 输出逐条校验（坏条目丢弃留好条目）；提取/摘要异步 + feature flag 采样。
- **为什么**：换 bot 抢占场景证明用户记忆应跟着 `wechatUserId` 走；单次微信会话通常 <10 轮，
  40 条阈值永远不会触发（功能等于没写）——触发节奏必须由真实场景反推。
- **为什么逐条校验而非整批跳过**：JSON 偶发一条坏数据就丢整轮是放大损失——最坏丢 1 条，
  而不是丢 5 条；坏条目 WARN 带原始片段，供调 prompt。
- **为什么不是全量每次都提取**：每轮提取 + 每 10 轮摘要是两套 LLM 调用，成本翻倍——
  `sample-rate` 先满跑一周观测账单，超预算降到 0.5 是显式可调策略而非隐藏假设。

### D-10 心跳=消息轮询：修复与测量（反编译驱动的性能优化）
- **决策**：`heartbeatIntervalMs` 30s → 3s；`onMessages` 只入队（per-bot 单线程执行器，队列 100，
  满则丢弃 + WARN）；心跳失败阈值 3 → 10。
- **为什么**：反编译 SDK 证实"心跳任务 = `pollAndDispatchMessages()`"（scheduleWithFixedDelay），
  消息最坏等 30s；且 onMessages 同步分发会让 LLM 处理阻塞轮询线程，形成"处理越慢轮询越慢"的恶性循环。
  修复必须同时做"缩短间隔（投递）"与"异步化（处理）"，否则只改间隔被处理耗时完全抵消。
- **为什么阈值同步上调**：间隔缩短 10 倍后，3 次失败 ≈ 9-15s 就会触发降级——网络抖动即误伤；
  10 次 ≈ 30s 失败窗口是间隔调整的必然连带，不是顺手改配置。
- **为什么暂不做**"正在输入/typing 占位"与更多降级优化：一次改动聚焦一个因果链，
  先可度量再谈体验；盲目加反馈层会掩盖真正的延迟来源。

### D-11 意图检测并入 FC（省一次 LLM 往返）
- **决策**：意图指令并入 FC system prompt——语音回复 `[VOICE]`、切换音色 `[VOICE_SWITCH:名]`、
  画图调 `generate_image` 工具；独立意图检测退化为**强信号关键词硬路由**（零 LLM）；
  硬路由服务失败 → 先提示"因为[原因]暂时不能提供服务" → 再走文本回复。
- **为什么**：原链路每条文本 = 意图检测 1 次 + FC 1+ 次，串行 LLM 往返直接进延迟；合并后
  普通问答路径只付一次 FC 调用。
- **为什么保留硬路由而非纯标记协议**：LLM 不保证遵守 `[VOICE]` 约定，纯协议会丢意图；
  硬路由用保守强信号（"画一张/帮我画"而非"画个"）拦截明确意图，弱信号才交给 LLM。
- **为什么不是"硬路由失败就报错"**：边界误判（如"我想画个流程图"其实是文本需求）不该让用户吃
  错误——提示原因 + 文本兜底保证回复不破。

### D-12 可观测性与管理面
- **决策**：MetricsService（ThreadLocal 聚合单条消息 + 全局计数 + 延迟分位）；
  链路计时日志携带 traceId 并**跨线程传递**（消息执行器 → 记忆 worker 经 MDC）；
  管理 API `/api/**` 用 `X-API-Token`（常量时间比较），只暴露只读的 `/api/health`、`/api/metrics`。
- **为什么**：性能优化没有度量等于没做——avg/p50/p95 与 FC/LLM 计数是验证"轮询提速 + 异步化"
  是否生效的证据；管理面刻意不暴露任何修改记忆/发送消息的接口，防恶意调用。
- **已知遗留（如实记录）**：`/bot/**` 创建/销毁/二维码接口尚未套同一 token——管理面收口完成了
  一半，剩余部分待后续统一挪入 `/api` 命名空间或加拦截器。

### D-13 广度取舍：MCP / RAG / 企业级记忆治理
- **决策**：项目深度的优先级高于功能广度；Context Manager 先于 MCP；RAG 文档知识库放最后，
  与长期记忆共用检索基建；不做企业级记忆治理（分支/PR 合并语义、规则版本同步、上下文健康 CI）。
- **为什么**：面试项目最怕"功能多但一问核心路径的测试/度量/安全就露馅"——可信度（测试 + CI +
  可观测）先于生态广度（MCP）。记忆治理里的**审查/溯源**与**文件系统卸载思想**被吸收为
  provenance 字段与"记忆进 DB、产物落盘"的分层；**合并语义**被砍是因为记忆是
  per-conversation 隔离，无分支合并场景——"知道什么不该做"本身就是判断力。
- **为什么 RAG 不用于压缩聊天记录**：记忆压缩是上下文管理问题（窗口 + 摘要 + 长期记忆），
  检索是另一回事；概念错位会在面试追问时露怯，正确叙事是"我研究过企业级方案并做了场景化取舍"。
