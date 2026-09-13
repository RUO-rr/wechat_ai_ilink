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

### v2.5 → v2.6 RAG 检索链路（文档知识库）

```
需求落点确认：RAG 服务的是「外部知识」，不是聊天记录压缩（D-13 已澄清）—— 落到文档知识库：
  ├─ 来源一：内置知识资产 resume-builder/**（方法论 / 模板 / 案例 / 评价标准，44 份 ≈ 155 KB）
  └─ 来源二：用户上传文件（data/documents、data/resumes）

写入侧（灌库）
  ├─ 标题感知切分：Markdown 标题 → 语义小节（记录标题路径供引用）
  │    └─ 超长小节再按「段落 → 句子 → 硬切」降级，相邻片段带重叠，避免答案正好落在切口上
  ├─ 双重幂等：content_hash（内容没变不重建）+ embedding_model（换了向量模型自动重建）
  ├─ 向量化失败不阻断：片段照常入库（embedding = NULL），只走关键词召回
  └─ 启动异步灌库：单线程守护线程，重启先用库里片段恢复内存索引，不重复调用向量模型

读取侧（检索）—— 四段式
  ├─ 双通道召回：向量余弦（语义泛化）∥ BM25（字面命中，中文 bigram）
  ├─ 分数各自归一化后加权融合（默认 0.65 : 0.35），按「文档#片段」去重合并
  ├─ 可选精排：DashScope gte-rerank 逐对打分（默认关闭；失败/不支持自动退回）
  └─ 单文档配额 + 字符预算 → 带「来源文件 + 章节 + 相关性」的引用上下文

接入点
  ├─ search_knowledge 工具（domain=general）：模型自主决定何时翻资料
  └─ ChatFileService：文件不再截前 2000 字，改为「全文入库 + 覆盖率采样摘要」
```


### v2.6 → v2.7 长期记忆语义召回（复用检索基建）

```
问题：记忆读路径按 id 倒序取最近 5 条 —— 记忆一多，近期但无关的记忆会挤掉真正相关的那条；
      写路径的冲突解决靠 dimension 字符串精确匹配（answer_style 与 reply_style 被当成两个维度）。

做法：把 RAG 的打分内核抽成通用件，两条链路共用，而不是复制一份 BM25 ——
      HybridIndex（向量余弦 ∥ BM25，copy-on-write 快照）  +  HybridFusion（归一化 + 加权融合，面向 Hit 接口）
      ├─ 知识库侧：KnowledgeVectorIndex / KnowledgeRetriever 改为委托内核（对外行为不变，33 个既有测试未改）
      └─ 记忆侧：MemoryIndex（按用户隔离的索引）+ MemoryRetriever（查询向量化 → 双通道 → 融合）

读路径：记忆槽 = 最近 N 条保底 ∪ 与当前问题相关的召回（补足到 5 条），摘要槽不变
        └─ 索引不可用时整体退回纯 recency —— 检索可以退化，回复不能中断

写路径：LLM 判定 new 时用余弦相似度兜底去重（阈值 0.92，只用绝对值，不用归一化分）
        └─ supersede 不查重：那是 LLM 看过现有记忆后的显式判断，不该被启发式推翻

装载：按用户惰性装载（首次访问时拉该用户 active 记忆，批量向量化后建索引）
      └─ 记忆向量不落库：条目少、变更频繁，落库的写放大不划算；重启后用一次批量向量化换回来
```

### v2.7 → v2.8 向量库落地：换之前先证明「该换」

```
问题：检索内核把向量和文本都放在 JVM 堆里 —— 到十万片段时向量本身就占几百 MB，
      而且向量召回是暴力扫描 O(语料)，每次提问都要扫一遍全量。
      候选：Qdrant（专用向量库，HNSW）/ pgvector（复用 MySQL 生态）/ 继续留在进程内。

选型：Qdrant。十万级片段正是 HNSW 的收益区间；单机二进制与 Docker 两种跑法都不改代码；
      元数据过滤走 payload 索引；检索压力不压到主库。

做法：只把「会随规模变化的那一件事」抽出来 —— 向量落在哪、怎么比：
      RetrievalIndex（端口：写入 / 读取 / 统计三件事收口）
      ├─ InMemoryRetrievalIndex（默认）：两通道都在堆内，零外部依赖
      └─ EmbeddingStoreRetrievalIndex：向量落 LangChain4j EmbeddingStore（接 Qdrant），
                                     关键词通道仍在堆内（BM25 依赖全文，换存储没有收益）
      两个实现对外行为一致：同样的命中结构、同样「只比同模型向量」的约束
      → 上层的混合召回与融合逻辑不知道自己在哪种 provider 上跑

装配：VectorStoreConfiguration 按 vectorstore.provider 选实现；Qdrant 连不上就降级成进程内 + 告警，
      不让「向量库没起来」变成「服务起不来」。

结论：接得上，但不默认开 —— 实测数据与启用条件见 2.16 与 D-16。
```

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
- 测试与 CI：121 个单测（FC 编排 / ConversationHistory / BotManager / ToolRouter / Memory / RAG / 记忆检索）；
  GitHub Actions 以服务容器起 MySQL 8.4 + Redis 7.4 跑 `mvn test`（本地无 MySQL/Redis 时排除 @SpringBootTest 上下文用例）

---

### 2.14 RAG 检索链路（v2.6）

- **检索单元**：`knowledge_document` / `knowledge_chunk` 两张表；片段向量以 JSON 文本存储（可读、可审计、
  换向量库时可直接迁移）。语料规模：内置资产 44 份 → 424 片段（实测），加上用户上传文档仍在千级以内；
- **混合检索**：向量（语义泛化）+ BM25（专有名词 / 字段名 / 编号的字面命中）两路各自归一化后加权融合，
  `rag.fuse.vector-weight` 可调（默认 0.65 偏向语义）；两条通道互补，任何单路都会漏召回
- **四层降级**：① 查询向量化失败 → 本次只走关键词；② 写入向量化失败 → 片段仍入库（embedding 为 NULL）；
  ③ 精排不可用 → 按召回分排序；④ 知识库为空 → 返回空上下文并明确告知模型「不要编造引用」
- **向量空间隔离**：片段记录 `embedding_model`，检索只比较同模型同维度的向量 —— 本地降级向量与 DashScope
  向量不会互相污染；"换模型 = 重建索引"由 content_hash + embedding_model 共同决定
- **降级向量**：无 DashScope 密钥（本地开发 / CI）时使用词法哈希向量（feature hashing + 1+log(tf) + 符号哈希 +
  L2 归一化），让链路可用、单测不依赖网络，同时把能力边界写在类注释里（只有字面召回，没有语义泛化）
- **索引结构**：不可变快照 + copy-on-write 发布，读路径无锁；写入（重建 / 增量 upsert / 删除）整体换引用，
  读侧永远看到完整快照，不会读到半个索引
- **接入**：`search_knowledge` 工具（domain=general，任何路由分支都带上）+ ChatFileService 的
  「全文入库 + 覆盖率采样摘要」，替换了原来的「截取前 2000 字」（长文档的中段与结尾此前完全不可见）
- **可观测**：`/api/metrics` 增加 ragRetrievals / ragAvgMs / ragEmptyResults；`/api/knowledge` 给出文档数、
  片段数、向量化比例与当前向量模型，用于判断「知识库到底有没有东西」
- **测试（31 个新增）**：切分（标题路径 / 长度约束 / 纯文本降级）、哈希向量（确定性 / 归一化 / 相似度排序）、
  灌库（指纹幂等 / 换模型重建 / 向量失败降级 / 覆盖率采样到文末）、检索（引用组装 / 单文档配额 /
  关键词兜底 / 精排调序 / 空库短路）

---

### 2.15 长期记忆语义召回（v2.7）

- **检索基建复用**：把「向量 ∥ BM25 + 归一化加权融合」抽成 `HybridIndex`（索引内核）与 `HybridFusion`
  （融合层，面向 `Hit` 最小接口），知识库与长期记忆共用 —— 记忆侧没有第二份 BM25 实现；
  知识库侧改为委托内核后对外行为不变（既有 33 个 RAG 测试一行未改）
- **读路径**：记忆槽 = 最近 N 条保底 ∪ 与当前问题相关的召回（补足到 5 条）。
  只按相关性召回会让「回答要简洁」这类全局偏好在没命中的轮次里消失；只按新旧又会被无关记忆占满配额
- **写路径**：LLM 判定 `new` 时用余弦相似度做去重兜底（默认阈值 0.92，用绝对值而非归一化分）——
  `answer_style` / `reply_style` 这类同义 dimension 写法不同也能拦住重复；`supersede` 不查重，
  那是 LLM 看过现有记忆后的显式判断，不该被启发式推翻
- **按用户惰性装载**：首次访问某用户时才拉取他的 active 记忆建索引（`memory.index.max-entries-per-user` 封顶），
  启动不预热、不拖慢启动；索引已装载时写入同步更新，未装载则不动（库里已是最新，下次装载自然带上）
- **三层降级**：检索关闭 / 装载失败 → 退回纯 recency 注入；向量化失败 → 该批只走关键词通道；
  库为空 → 保底配额照常注入。任何一层失败都不会让回复中断
- **可观测**：`/api/metrics` 增加 memoryRecalls / memoryRecallAvgMs / memoryRecallEmpty，与 RAG 的
  ragRetrievals 分开计数（记忆召回为空 = 没记住，检索为空 = 没资料，排查方向不同）
- **测试（40 个新增，v2.7 时合计 121）**：索引内核（BM25 排序 / 向量空间隔离 / 维度不匹配 / 同 key 覆盖 / 空查询）、
  融合（双通道合并 / 通道标注 / 单通道降级 / 权重夹取 / 全零不产生 NaN）、
  记忆索引（装载幂等 / 失败不缓存 / 向量化降级 / 覆盖与删除 / 判重取余弦绝对值）、
  召回（相关性优先 / 类型过滤 / 索引不可用返回空）、记忆服务（保底 + 召回配额 / 去重跳过 / 摘要不进索引）

### 2.16 向量库接入与「实测驱动的默认值」（v2.8）

- **一次抽象换两种部署形态**：`RetrievalIndex` 把「向量落在哪、怎么比」收成一个端口，两个实现共用同一套
  语义（命中结构、`embedding_model` 隔离、删除与覆盖、降级路径），上层检索与融合对 provider 无感知；
  切换只是配置值改一处（`vectorstore.provider=qdrant`）
- **Qdrant 侧的三个工程细节**：① 点 id 由 `UUID v3(文档#片段)` 派生 —— 重复回灌天然幂等，
  不需要「先清集合再灌」；② 引用要用的元信息（来源文件 / 标题 / 章节 / 模型 / 片段号）扁平存进 payload，
  命中后不回查 MySQL 就能拼出引用；③ 写入失败进退避窗口（默认 30s）且只记一次告警（首条带完整栈），
  窗口内检索直接走关键词通道 —— 远端抖一下不会把每条查询都变成一次超时等待
- **关键词通道留在进程内**：BM25 依赖全文分词与倒排，文本本来就要驻留内存，换存储省不下文本，
  只会给每次检索加一次网络往返；真正省得下的是向量那一块

实测数据：合成语料，`VectorStoreBenchmarkTest`，dim=256 / 100 次查询 / top-10，单机 Qdrant 1.19。
四种跑法吃同一份数据（向量通道都取 30 个候选再融合），`recall` 以进程内暴力检索的 top-k 为精确基准。
完整报告（含运行环境与复现命令）：[docs/bench/vector-store-benchmark.md](docs/bench/vector-store-benchmark.md)

| 语料片段 | 向量通道 | 建索引 | p50 | p95 | recall@10 |
|---|---|---|---|---|---|
| 10000 | 进程内暴力（向量） | 568 ms | 3.07 ms | 4.29 ms | 1.000（基准） |
| 10000 | 进程内暴力 + BM25 融合 | 568 ms | 21.38 ms | 26.20 ms | 1.000（基准） |
| 10000 | Qdrant HNSW（向量） | 1642 ms | 3.71 ms | 6.96 ms | 1.000 |
| 10000 | Qdrant + 内存 BM25 融合 | 1642 ms | 20.65 ms | 27.47 ms | 1.000 |
| 100000 | 进程内暴力（向量） | 3209 ms | 31.00 ms | 36.10 ms | 1.000（基准） |
| 100000 | 进程内暴力 + BM25 融合 | 3209 ms | 231.06 ms | 250.32 ms | 1.000（基准） |
| 100000 | Qdrant HNSW（向量） | 11409 ms | 3.80 ms | 10.31 ms | 0.692 |
| 100000 | Qdrant + 内存 BM25 融合 | 11409 ms | 199.50 ms | 310.45 ms | 0.692 |

堆占用（GC 后实测增量）：一万片段 40.7 MB（关键词 + 元信息）→ 50.4 MB（再加向量）；
十万片段 404.3 MB → 503.6 MB —— 切向量库省下的正是后面那一段。

- **数字读出来的三个结论**：① 一万片段时 Qdrant 没有优势（向量通道 3.71ms vs 3.07ms，反而略慢），
  建索引还慢 2.9 倍；② 十万片段时向量通道快 8 倍（3.80ms vs 31.00ms），但**端到端融合只快 14%**
  （199.50ms vs 231.06ms）、p95 反而更差（310.45ms vs 250.32ms）—— 耗时几乎全被内存里的 BM25 吃掉，
  换掉向量通道并没有换掉瓶颈；③ HNSW 是近似检索，十万片段 recall@10 = 0.692，
  而内存暴力扫描是精确解 1.000，这是换向量库真正要付的账（近似索引每次建图会略有浮动：
  两次实跑分别是 0.740 / 0.692）
- **所以默认值仍是 `in-memory`**：当前真实语料只有几百个片段，引入向量库是净亏（多一个组件、建索引更慢、
  recall 有损）。Qdrant 作为可切换 provider 保留，启用条件与选型理由见 D-16
- **顺带量出一个真缺陷（如实记录）**：关键词通道是 copy-on-write，**每次增量写入都会整体重编译一次倒排** ——
  实测「追加 1 份文档（10 片段）」在一万片段语料上 56.2ms、十万片段上 396.5ms，随语料线性增长。
  真实规模（几百片段、每次入库一份文档）这份开销可忽略，但批量入库必须走全量重建路径；
  要让增量写入也变成 O(新增)，需要给倒排加增量合并 —— 本次主动不修，触发条件与修法见 D-16 遗留项 ①
- **测试（14 个新增，合计 135）**：端口实现（引用元信息往返 / 向量不占堆 / 模型过滤 / 覆盖不留幽灵点 /
  双通道删除 / 空集合才回灌 / 远端失败降级与退避窗口后恢复探测 / 点 id 稳定性）、
  Qdrant 集成（payload 穿 gRPC 往返 / 按模型过滤 / 按 document_id 删除 / 覆盖清旧点 / 集合非空跳过回灌，
  无 Qdrant 时自动跳过）、基准（`-Dbench.enabled=true` 才跑，产出 md 报告）

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
| 向量通道收口 | 向量落在哪抽象为 RetrievalIndex 端口，内存与 Qdrant 两实现共用同一套检索语义与降级（v2.8） |

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
| 单测 | 135 个（FC 编排 / ConversationHistory / BotManager / ToolRouter / Memory / RAG / 记忆检索 / 向量库端口与集成） |
| 向量通道 | 默认 in-memory；可切 Qdrant（10 万片段实测 p50 3.80ms / recall@10 0.692，见 2.16） |
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
- **后续落地**：RAG 文档知识库于 v2.6 落地（见 2.14 与 D-14）；长期记忆复用同一检索基建于 v2.7 落地
  （见 2.15 与 D-15）——"先深度后广度"的排序未变，只是按顺序轮到它们。

### D-14 RAG：让检索可解释、可降级
- **决策**：RAG 落在文档知识库（内置资产 + 用户上传），采用「标题感知切分 + 向量 ∥ BM25 混合召回 +
  可选精排 + 带引用上下文」；向量存 MySQL（JSON 文本）并在进程内建索引，不引入独立向量数据库。
- **为什么混合而不是纯向量**：中文技术文档里大量字面命中（字段名、编号、专有名词）会被向量平滑掉，
  而语义泛化只有向量能覆盖；两路分数归一化后融合，比任何单路都稳。
- **为什么不上一套向量数据库**：当前语料千级片段（内置资产实测 424 段），内存暴力检索毫秒级返回；引入 Milvus / pgvector
  是"为规模假设买单"。索引收敛在 `KnowledgeVectorIndex` 一个类里，换实现不影响检索侧接口。
- **为什么每个环节都要能降级**：没有密钥、模型不通、精排模型不在当前 SDK 版本里 —— 三种情况都只降低
  检索质量，不打断主链路；检索为空时显式告诉模型"不要编造引用"，比让它自由发挥安全得多。
- **为什么向量要带模型标识**：本地哈希向量与线上真实向量语义空间不同，混在一起会给出"看着有分数、
  其实不可比"的排序；用 embedding_model 隔离 + 换模型自动重建，换来的是排序可解释。
- **已知遗留（如实记录）**：① ~~长期记忆仍走 dimension 精确匹配~~ → v2.7 已复用本检索基建（见 2.15 / D-15）；
  ② 内置资产的来源路径依赖资源 URL 解析（非常规类加载器下会退化为文件名）；
  ③ 精排默认关闭 —— 开启需要把 dashscope-sdk-java 从 2.16.7 升到 ≥ 2.22（避免影响现有 STT 链路，未升级）。

### D-15 长期记忆检索：保底 + 语义召回（否决「纯相关性」与「扩配额」）
- **决策**：记忆读路径改为「最近 N 条保底 ∪ 与当前问题相关的召回」，召回复用 RAG 的混合检索内核；
  写入侧用余弦相似度做去重兜底（只在 LLM 判定 `new` 时生效）。
- **为什么不是纯相关性召回**：记忆里既有「跟当前问题相关」的事实，也有「与任何问题都不相似但一直生效」的全局偏好
  （回答要简洁、别用 Markdown）。纯相关性会让后者在没命中的轮次里静默消失 —— 用户只会觉得「它怎么又忘了」，
  而这种退化很难从日志里发现；保底配额把「长期有效」这件事显式建模。
- **为什么不是把配额从 5 提到 20**：注入的每一条都进 prompt，成本与干扰线性上涨；而且「多塞几条」不解决「塞错了」——
  真正的问题是选错，不是选少。
- **为什么写入去重用余弦绝对值而不是融合分**：融合分是「本次候选里的相对高低」，同一条记忆在不同查询下可以是 1.0
  也可以是 0.3；判重问的是「这两条是不是同一件事」，只有余弦绝对值能回答。
- **为什么只在 new 上查重**：supersede 是 LLM 看过现有记忆后的显式判断，若被启发式拦下，用户改了口味而记忆没更新，
  比重复一条更糟 —— 宁可在 new 上少写一条，也不推翻显式冲突解决。
- **为什么记忆向量不落库**：记忆条目少（每人几十条）、变更频繁（每次 supersede 都换内容），落库要多一张表 +
  每次写入多一次写；用「按需装载时批量向量化一次」换回来更划算。条数涨到千级时再把向量落库即可 ——
  换的是 `MemoryIndex` 的装载实现，检索侧接口不变。
- **已知遗留（如实记录）**：① 记忆索引按用户惰性装载，重启后首次访问会同步做一次批量向量化（配了真实密钥时
  约一次模型往返）；② 去重阈值 0.92 只按「同义 dimension 写法不同」这类场景调过，未做大规模标注评估。

### D-16 向量库：默认仍留在进程内（实测后主动否决「先接上再说」）

- **决策**：接入 Qdrant 作为可选 provider（`vectorstore.provider=qdrant`），**默认值保持 in-memory**，
  并把基准数据与启用条件一起写进文档。
- **为什么要接**：项目里唯一会随规模失控的是向量（1024 维 × 10 万片段 ≈ 400MB 堆占用）与暴力扫描
  （每次查询 O(语料)）。把一个真实向量库接进来，是验证这个抽象站不站得住的唯一方式 ——
  接口设计对不对，换个实现才知道；顺带把「该不该开」这个问题从感觉变成数字。
- **为什么不是「既然做了就默认开启」**：实测不站在这一边（2.16）：一万片段时向量通道 3.71ms 对比
  内存 3.07ms，谈不上优势；十万片段向量通道快 8 倍，但端到端融合只快 14%、p95 反而更差，瓶颈在内存 BM25；
  同时建索引慢 2.9 倍、recall@10 从 1.000 掉到 0.692。当前真实语料只有几百个片段，
  开它等于纯付成本 —— 一个只在十万级才回本的组件，不该成为几百级语料的默认路径。
- **为什么不是 pgvector**：它把向量检索压回主库（MySQL），收益要到更大数据量才明显，
  而本项目知识库是「几百到十万片段」这一档；两台组件各自伸缩，比把两种负载混在一台库里更可控。
- **为什么关键词通道不搬进向量库**：BM25 依赖全文分词与倒排，文本本来就要驻留内存；
  把倒排搬出去省不下文本，只会给每次检索加一次网络往返。
- **已知遗留（如实记录，附触发条件 —— 写下来是为了到点能认出来，而不是留一句「以后优化」）**：
  - **① 逐份写入仍是 O(语料)（本次主动不修）**：关键词通道 copy-on-write，每写一份文档都整体重编译一次
    倒排 —— 实测 1 万片段 56ms / 10 万片段 396ms，成本随语料线性增长（基准里 10 万份逐份写入直接跑不完）。
    **为什么现在不修**：真实写入路径是「用户上传一份文档」，语料几百个片段，单次不足 1ms，
    修它的收益为负（真正的增量倒排要付下面的代价）。
    **影响面**：只影响上传接口的响应时间；不改变检索质量与问答正确性 —— 写入变慢或失败都不会让回复中断
    （片段仍入库，只是索引更新滞后）。
    **触发条件（满足任一条就该动手）**：a) 单次批量导入超过约 1000 份文档；b) 语料涨到 1 万片段以上，
    或出现「一次灌库跑到分钟级」的现象。
    **到那时怎么修**：优先做「批量入口合并」—— 一次导入合并成一次重编译（`RetrievalIndex` 加批量写入，
    启动扫描与多文件导入改走该入口，约百行改动，不碰读路径）；只有当「逐份上传」本身也成为瓶颈时，
    才值得做真正的增量倒排（把 `HybridIndex` 的倒排改成增量结构），代价是要么加锁（丢掉现在读路径
    无锁快照的特性），要么引入持久化集合 —— 属于规模触发后再做的事。
  - **② HNSW 未调参**：`ef` / `m` 用默认值，未按语料调；十万片段 recall@10 在 0.69~0.74 之间浮动
    （近似索引每次建图略有不同，两次实跑已记录在 2.16）。
  - **③ 运维边界**：未做副本与持久化运维 —— 数据可从 MySQL 片段表全量回灌，所以不把 Qdrant
    当作持久化权威（它随时可以删掉重建）。
