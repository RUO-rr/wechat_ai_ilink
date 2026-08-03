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
    ├── WordDocumentTool.java  ← 聚合路由器（内部收集 6 个 WordOperation 策略）
    ├── SearchCompanyInfoTool.java
    ├── SearchIndustryNewsTool.java
    └── word/                   ← Word 域策略模式
        ├── WordOperation.java  ← 策略接口
        ├── CreateDocumentOp.java
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
- 闭包注入 `botId`：`BotMessageListener` 构造函数接收 `BotInstance`，回调时通过 `BiConsumer<String, WeixinMessage>` 传入 `botId`
- `BotContext`（ThreadLocal）在消息入口 set，`finally` 块 clear

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

---

## 三、代码质量改进

| 改进 | 说明 |
|------|------|
| 职责拆分 | ChatTextService 从 360 行 → 110 行，提取 IntentDetectionService / SpeechTextGenerationService / FunctionCallingOrchestrator |
| 共享 Bean | RestTemplateConfig 统一超时配置（连接 5s / 读取 60s），消除 6 处 `new RestTemplate()` |
| LlmClient | 封装 OpenAI 兼容 API 的 URL 构建/Header/响应解析，消除 15+ 处重复代码 |
| 日志安全 | 用户数据从 INFO → DEBUG，userId 脱敏，响应体不落盘 |
| 线程安全 | ConversationHistory 内部 `Collections.synchronizedList`，跨轮 getSnapshot 加锁拷贝 |
| 错误恢复 | DB 写入失败降级为仅内存，临时文件删除失败打 WARN 日志 |
| 身份模型 | 移除伪造 userId 链路，以 SDK LoginContext 为唯一信任根 |

---

## 四、关键指标

| 指标 | 数值 |
|------|------|
| 支持 Bot 数 | ≤ 10（可配置，`bot.max-bots`） |
| 工具总数 | 8 个 ToolDefinition 实现 |
| Word 域操作数 | 7 个 WordOperation 策略 |
| FC 循环上限 | 15 步 + 2 次重复保护 |
| systemBotId 碰撞概率 | 36^8 ≈ 1/2.8万亿 |
| 缓存 TTL | 30 分钟 |
| 缓存隔离 | Redis key `chat:history:{botId}:{userId}` 复合键 |
| 数据层 | MySQL 8（InnoDB / utf8mb4）+ Redis 7（缓存） |
| 重启恢复 | 全自动（bot_registry 持久化 LoginContext + 免扫码恢复） |
| 编译结果 | 零 ERROR |
