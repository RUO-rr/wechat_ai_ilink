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

### v2.8 → v2.9 检索质量评测：把「混合更好」从口号变成数字

```
问题：v2.6 上线的混合召回（向量 ∥ BM25，0.65 : 0.35）里，融合权重是凭经验定的。
      「混合比单路好多少」在仓库里没有一个数字；更麻烦的是后续任何调整
      （换 embedding 模型、改权重、调切分参数）都没有回归的锚点。

做法：不写一次性脚本，而是建一份可复现的评测集 + 评测器（评测本身就是一个测试）：
      src/test/resources/rag-eval/      33 篇公开文档 / 171 个切分片段（五个主题）
      ├─ questions.tsv                  26 道题：问题 / 期望文档 / 答案里必然出现的字面串
      └─ sources.tsv                    来源 URL + 抓取时间（谁都能重抓复核）
      RagEvaluationTest                 三通道同口径（向量 / BM25 / 混合），
                                        指标 Hit@1 / Hit@5 / MRR@5，文档级 + 片段级各一套
      装载即自检：标注的字面串必须真出现在期望文档的片段里，标注写错直接失败 ——
      避免把「标注错了」记成「检索不行」。

结论（离线词法向量口径，完整报告见 docs/bench/rag-eval.md）：
      文档级 Hit@5：BM25 0.962 > 混合 0.885 > 向量 0.846
      文档级 Hit@1：混合 0.808 > BM25 0.769 > 向量 0.692
      权重扫描 w ∈ [0.20, 0.80] 曲线几乎是平的：离线哈希向量与 BM25 吃同一批 token，
      候选高度重合 —— 混合换来的是「头部排序更稳」，代价是尾部召回被向量稀释。
      → 所以断言只守「混合不弱于它融合的向量通道 + 绝对底线」，真实模型下重跑才是验证。
```

### v2.9 → v2.10 切分器对照：把「自研切分够不够好」变成可量的问题

```
问题：从 v2.6 起，文本切分只有一种实现（自研标题感知切分），800/120 这组参数是凭经验定的 ——
      「切得好不好」既没有参照物，也没有回归口径。换不换成框架的拆分器，纯靠感觉。
      候选：只留自研 / 全面换成 LangChain4j / 抽端口让两者并存并实测。

做法：把切分抽成 TextSplitter 端口（split(rawText) → List<Chunk>），配置 rag.splitter 二选一：
      ├─ self（默认）：TextChunker —— 标题感知（Markdown 标题 → 小节 → 段落/句子/硬切 + overlap），
      │                片段带标题路径，引用能定位到小节
      └─ langchain4j：Langchain4jTextSplitter —— DocumentSplitters.recursive(maxChars, overlap)，
                       适配层只做 Document/TextSegment 映射，不掺自研逻辑（掺了对照就不成立）
      两者产出同一个 DTO：索引、检索、融合、引用拼装一行都不用改；
      评测对两种切分各建一次索引、跑同一批题，并各自跑一遍标注自检。

结论（离线向量口径，详见 docs/bench/rag-eval.md 的「切分器对照」）：
      文档级 Hit@5 打平（0.885 : 0.885）；Hit@1 自研 0.808 vs 框架 0.769，MRR 0.846 vs 0.827；
      框架版片段更少更长（152 个 / 均长 579 字符 vs 171 个 / 548 字符）；
      关键是框架切分不认识 Markdown 标题 —— 片段没有标题路径，引用定位退化。
      → 默认留在自研，框架实现作为可切换 provider；什么时候该切，条件写在 D-18。
```

### v2.10 → v2.11 接上框架原生那一整条：naive RAG 基线与自己比一次

```
问题：项目里真正自研的是「检索那一整条」—— 混合召回、融合权重、引用拼装、降级。
      它值不值这些代码？缺一条公认的基线。另一件事：LangChain4j 的 EmbeddingStoreIngestor
      是「切分 → 向量化 → 落库」的标准做法，生产写入路径要不要也换过去？

做法：a) 先确定能不能换 —— 反编译看 Ingestor 到底怎么落库（不看文档措辞，看字节码）；
      b) 在评测里搭一条教科书式 naive RAG：Document（带 doc_id 元数据）→ EmbeddingStoreIngestor
         → InMemoryEmbeddingStore → EmbeddingStoreContentRetriever（向量单路 top-5），
         同一份语料、同一批 26 题、同一个离线向量模型，与自研链路同口径比。

结论一（能不能换：不换）：Ingestor 落库调的是 EmbeddingStore.addAll(embeddings, segments) ——
      点 id 由 store 自动生成，用不到 addAll(ids, embeddings, segments) 那套显式 id 接口；
      而我们的向量通道依赖「文档#片段 → UUID v3」的稳定点 id（重复灌库是覆盖不是新增），
      且 MySQL 才是权威数据源（向量库随时能删掉重建）。
      → 换过去只能替掉十几行胶水，代价是两个不变量，不划算（见 D-19）。

结论二（基线成绩）：文档级 Hit@5 打平（0.885 : 0.885），自研在文档级 Hit@1/MRR 更好
      （0.808 / 0.846 vs 0.731 / 0.801），片段级全面领先（Hit@1 0.769 vs 0.577、
      Hit@5 0.885 vs 0.846、MRR 0.827 vs 0.684）。
      → 差距最大的是片段级，而片段级正是拼 prompt 最要紧的一档：引用要准，模型才看得到答案。
      这条基线从此成了「自研检索值不值」的尺子，后面任何改动都能拿它比。
```

### v2.11 → v2.12 换真实向量模型复跑：预测被数据推翻

```
问题：此前所有检索结论都建立在离线哈希向量上（零网络、可进 CI，但它与 BM25 吃同一批 token，
      向量通道被系统性低估）。「换成真实语义向量，混合应该就能赢」一直只是个预测，没人验证过。
      评测新增 -Drag.eval.model=dashscope：换上 DashScope text-embedding-v4（1024 维）跑同一批题。

结果：向量通道确实变强 —— 文档级 Hit@5 从 0.846 涨到 0.962（与 BM25 打平），
      说明「离线口径低估向量」这个判断是对的。
      但混合还是没赢，这次连纯向量都排在它前面（文档级 Hit@1 / MRR）：
        混合 0.692 / 0.795  <  BM25 0.769 / 0.844  <  框架纯向量 0.769 / 0.854
      权重扫描 w ∈ [0.20, 0.80] 最好的一档也只有 0.731 / 0.827，没有任何一条超过纯 BM25。
      片段级更明显：真实向量把 Hit@1 从 0.538 掉到 0.423，BM25 却是 0.769。

结论：被推翻的是「模型太弱才导致混合不赢」这个假设 —— 换了模型，混合依然输。
      病灶指向**融合策略本身**（两路分数量纲不同，归一化加权压不准），
      也就是 D-17 遗留 ⑤ 预先写好的触发条件：现在触发了，下一步把 RRF（rank fusion）拉进对照。

成本：一次完整复跑约 90 秒（四份索引：主表 + 两种切分对照 + 框架基线各嵌一遍），
      金额按 DashScope 最小计费档；CI 仍跑离线口径，key 只用于本地复跑。
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

### 2.17 检索质量评测集与三通道对照（v2.9）

- **要回答的问题**：混合检索（向量 ∥ BM25）比单路好多少？——用 33 篇公开文档 / 171 个片段、
  26 道人工标注题实测，而不是靠一句「混合更好」
- **评测集**：五类主题（Spring Framework 的 AOP 与事务、LangChain4j 的 RAG 文档、
  联合国宪章「敌国条款」与外交部表态、大熊猫保护、GTA6 文件泄密），
  每题标注「期望文档 + 答案里必然出现的字面串」，装载时逐条自检，标注错直接失败
- **三通道同口径**：候选数一致（topK × 3 = 15），只有「用哪几路、怎么融合」不同；
  切分参数与生产一致（标题感知，maxChars=800 / overlap=120），语料片段数 171
- **结果（离线 `local-hashing-v1`）**：文档级 Hit@5 —— BM25 0.962 > 混合 0.885 > 向量 0.846；
  文档级 Hit@1 —— 混合 0.808 > BM25 0.769 > 向量 0.692。融合的价值体现在头部排序，
  而不是尾部召回：向量通道把 BM25 已经排对的结果往下压了几位
- **权重敏感性**：w ∈ [0.20, 0.80] 之间文档级 Hit@5 稳定在 0.885，MRR 在 0.808~0.840 之间 ——
  离线哈希向量与 BM25 候选高度重合，权重调不动结果，说明当前该做的是换模型而不是调权重
- **失败样本如实保留**：宪章第 53/107 条这类文言条文，三种通道都吃不到；GTA6 判决类新闻
  只有 BM25 在 4~5 名捞回来。逐题明细（含 ✗）随报告一起落盘
- **测试**：1 个评测用例，内部跑满 26 题 × 3 通道 × 5 个权重；`-Drag.eval.vectorWeight=`、
  `-Drag.eval.corpus=`、`-Drag.eval.report=` 可覆盖权重、语料目录与报告路径

### 2.18 切分器端口与 LangChain4j 原生切分对照（v2.10）

- **缝放在哪**：`TextSplitter`（`split(rawText) → List<Chunk>`）—— 索引链路只依赖这个口子，
  `RagConfiguration` 按 `rag.splitter=self|langchain4j` 选实现并打日志说明选了谁；
  换切分器只影响片段怎么来，检索、融合、引用拼装完全无感（同一个 DTO）
- **两个实现的分工**：
  - `TextChunker`（默认）：标题感知 —— 先按 Markdown 标题切小节并记标题路径，再按
    「段落 → 句子 → 硬切」降级拆分、相邻片段留 overlap；引用能定位到「文档 > 小节 > 片段」
  - `Langchain4jTextSplitter`（可选）：`DocumentSplitters.recursive(maxChars, overlap)` 的薄适配 ——
    只做 `Document` 包装与 `TextSegment` 映射，**不掺自研逻辑**；片段 `heading` 恒为 `null`
- **对照方法**：同一份语料（33 篇 / 26 题）、同一个离线向量、同一条混合通道，两种切分各建一次索引，
  各自跑一遍「标注字面串必须落在某个片段里」的自检 —— 切分把答案切丢了也要能被发现，而不是记到检索头上
- **对照结果（文档级 / 片段级）**：Hit@5 打平（0.885 : 0.885）；Hit@1 自研 0.808 / 0.769 vs 框架 0.769 / 0.731；
  MRR 0.846 / 0.827 vs 0.827 / 0.801；片段数 171（均长 548）vs 152（均长 579）
- **为什么默认仍是自研**：命中率打平时，差别落在「排序质量」与「引用可解释性」两处 ——
  自研两项都更好，而引用定位到小节是本项目 RAG 的明示卖点（D-14）。框架版省下的只是 19 个片段的体积。
- **什么时候该切过去**：知识库以非 Markdown 为主（PDF/Word 抽出的文本本来就没有标题结构）、
  或准备用 `EmbeddingStoreIngestor` 把「切分 → 向量化 → 落库」整条交给框架时（链路同源更划算）
- **测试**：4 个新单测锁定适配层行为（空输入返回空、不超 maxChars、序号连续且覆盖原文首尾、
  框架版无标题路径而自研版有）；评测里两种切分器各跑一次对照

### 2.19 框架原生 naive RAG 基线：给「自研值不值」一把尺子（v2.11）

- **基线长什么样**：`Document`（带 `doc_id` 元数据）→ `EmbeddingStoreIngestor`（切分 → 向量化 → 落库）
  → `InMemoryEmbeddingStore` → `EmbeddingStoreContentRetriever`（向量单路 top-5）——
  教科书里 naive RAG 的标准写法，一行 AI Service 就能接上
- **同口径怎么保证**：同一份 33 篇语料、同一批 26 题、同一个离线哈希向量；判定代码复用同一套口径
  （文档级看元数据里的来源 id、片段级看片段文本是否含标准答案），所以差异只来自链路本身
- **成绩对比**（文档级 / 片段级）：

| 链路 | 片段数 | 文档 Hit@1 | 文档 Hit@5 | 文档 MRR | 片段 Hit@1 | 片段 Hit@5 | 片段 MRR |
|---|---|---|---|---|---|---|---|
| 框架原生 naive RAG（向量单路） | 152 | 0.731 | 0.885 | 0.801 | 0.577 | 0.846 | 0.684 |
| 自研（混合召回 + 引用拼装） | 171 | 0.808 | 0.885 | 0.846 | 0.769 | 0.885 | 0.827 |

- **读法**：文档级命中率打平，差距集中在**片段级**与**排序质量**——关键词通道把「答案那一片」
  顶上来，这正好是拼 prompt 最要紧的一档；框架链路也没有「哪一份文档哪一节」的引用定位
- **断言只守下限**：基线是参照物，不是 KPI，所以只断言「跑得通、不是废的」（文档级 Hit@5 ≥ 0.50）；
  参照物哪天反超自研，那才是该动手换的信号
- **顺带否掉一个方案**：生产写入路径不接 `EmbeddingStoreIngestor`——理由与字节码证据见 v2.11 段与 D-19

### 2.20 真实向量模型复跑：一个被数据推翻的预测（v2.12）

- **为什么要跑**：前面所有检索结论都建立在离线哈希向量上。它零网络、可进 CI，但与 BM25 吃同一批 token，
  向量通道被系统性低估 —— 所以「换成真实语义向量，混合就该赢」始终是个未验证的预测
- **怎么跑**：`-Drag.eval.model=dashscope` 把向量模型换成 DashScope `text-embedding-v4`（1024 维），
  key 与生产同源（`RAG_EMBEDDING_API_KEY`，缺省复用 `LLM_STT_API_KEY`）；其余全部不动 ——
  同一份语料、同一批 26 题、同一切分、同一融合权重，报告落 `target/bench/rag-eval-dashscope.md`
- **两个口径并排**（文档级 / 片段级）：

| 通道 | 离线 Hit@1 | 离线 Hit@5 | 离线 MRR | 真实 Hit@1 | 真实 Hit@5 | 真实 MRR |
|---|---|---|---|---|---|---|
| 向量余弦（文档级） | 0.692 | 0.846 | 0.769 | 0.731 | **0.962** | 0.822 |
| 关键词 BM25（文档级） | 0.769 | 0.962 | 0.844 | 0.769 | 0.962 | **0.844** |
| 混合 0.65 : 0.35（文档级） | 0.808 | 0.885 | 0.846 | 0.692 | 0.962 | 0.795 |
| 向量余弦（片段级） | 0.538 | 0.769 | 0.647 | 0.423 | 0.885 | 0.607 |
| 关键词 BM25（片段级） | 0.769 | 0.962 | 0.838 | 0.769 | **0.962** | **0.838** |
| 混合 0.65 : 0.35（片段级） | 0.769 | 0.885 | 0.827 | 0.692 | 0.962 | 0.801 |

- **读出来的三条**：
  ① 离线口径确实低估了向量（文档级 Hit@5 0.846 → 0.962），这条怀疑被证实；
  ② 但混合**仍然输**，而且这次连纯向量都排在混合前面（文档级 Hit@1 0.692 / MRR 0.795
  vs 纯向量 0.769 / 0.854）—— 「模型太弱导致混合不赢」这个假设被推翻；
  ③ 权重扫描 w ∈ [0.20, 0.80] 没有任何一档超过纯 BM25（最好 0.731 / 0.827）——
  问题不在两路的比例，而在**怎么合**（分数量纲不同，归一化加权压不准）
- **断言口径的调整（值得单独记）**：原先那几条按离线口径标定的回归线（如文档级 MRR > 0.80）
  只在默认口径生效；换模型属于实验，只守「跑得通」的通用下限。把实验数据塞进回归线，
  两边都会变形：既会让 CI 因为换模型而红，也会诱导「改断言去迁就结果」
- **下一步**：按 D-20 做 RRF（rank fusion）对照 —— 触发条件正是 D-17 遗留 ⑤ 预先写好的那条

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
| 单测 | 140 个（FC 编排 / ConversationHistory / BotManager / ToolRouter / Memory / RAG / 记忆检索 / 向量库端口与集成 / 检索评测 / 切分器） |
| 向量通道 | 默认 in-memory；可切 Qdrant（10 万片段实测 p50 3.80ms / recall@10 0.692，见 2.16） |
| 检索评测 | 33 篇文档 / 171 片段 / 26 题；文档级 Hit@5 混合 0.885（BM25 0.962、向量 0.846），见 2.17 |
| 切分器 | 默认自研标题感知（171 片段 / Hit@5 0.885）；可切 LangChain4j recursive（152 片段 / 0.885，但无标题路径），见 2.18 |
| 框架基线 | LangChain4j naive RAG（Ingestor + ContentRetriever）：文档 Hit@5 0.885 打平，片段 Hit@1 0.577 vs 自研 0.769，见 2.19 |
| 真实模型复跑 | DashScope `text-embedding-v4`（1024 维）：文档 Hit@5 0.846 → 0.962，但混合仍输 BM25，见 2.20 / D-20 |
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

### D-17 检索评测：接受「离线口径下混合未必赢单路」

- **决策**：把评测做成仓库里的可复现资产（语料 + 题目 + 来源清单 + 报告），默认跑离线词法向量，
  并把「BM25 单路更强」的实测结论原样写进文档；断言只守「混合 ≥ 同权重下的向量通道」+ 三条绝对底线。
- **为什么要**：v2.6 定下的融合权重 0.65 : 0.35 一直没有数据支撑；没有评测集，任何「换模型 /
  调权重 / 改切分参数」都只能靠感觉，也无法证明「这次改动没让检索退化」。
- **为什么断言不写「混合 ≥ 单路」**：离线哈希向量与 BM25 吃的是同一批 token（中文 bigram + 英文词），
  向量通道额外带来的只有 512 维哈希碰撞的噪声 —— 在这套口径下「混合 ≥ 纯关键词」本身就是伪命题。
  把断言写成想要的结果，评测就退化成装饰；真实对照要换成 embedding 模型（换模型只改 `#embed` 一处）。
- **为什么不用线上 embedding 模型做默认**：需要密钥与网络，CI 跑不起来、结果也不可复现；
  离线口径的代价（向量通道被低估）写进报告与 `rag-eval/README.md` 的「已知边界」，而不是藏起来。
- **已知遗留（写清触发条件，到点能认出来）**：
  - **①【面试前必补】题库偏置：26 道题都是「词面重叠型」** —— 出题时复用了原文措辞
    （`out of the box` / `entry point` / `@Pointcut` 这类词在题面和文档里同时出现），等于在 BM25 的主场
    跟它比。这份 `rag-eval/README.md` 自己写着「题型要混着来、要含同义改写」，实际没做到，是**已知的
    方法论漏洞**，不只是覆盖面问题：它意味着报告里「BM25 最强」有一部分是题目风格选出来的。
    **怎么修（两步，合计 1~1.5 小时）**：a) 补一组**成对改写题** —— 同一批事实、两种问法
    （原文措辞 vs 口语化改写），每组 ≥10 题，两组数字分别报，先写清假设再跑；
    b) 评测器加「分组」维度（题目 id 前缀或第 5 列 + 分组汇总，约 30 行），否则两组指标分不开。
    **预期结果（提前写下，免得事后找解释）**：离线哈希向量本质也是词面匹配，改写题很可能让三条通道一起掉分 ——
    那不是失败，它把结论从「混合不行」改成「瓶颈在 embedding 模型，不在融合策略」，是更有价值的证据。
    **不要做的事**：为了让数字好看去反复调题或调权重（data dredging）——那比不补更糟。
  - **② 真实 embedding 模型复跑（已完成，结论见 2.20 / D-20）**：向量通道确实被离线口径低估
    （文档级 Hit@5 0.846 → 0.962），但混合**仍然输给纯 BM25 与纯向量**，权重扫描也没有任何一档能赢 ——
    说明瓶颈在融合策略而不在模型。下一步按 D-20 做 RRF 对照。
  - **③ 语料是公开文档摘录**：33 篇 / 171 片段、五个主题，新闻类只取前若干段。它能证明的是
    三通道的相对强弱，**不代表生产语料（用户上传文档）的绝对召回水平**；触发条件：真实上传语料
    超过 300 篇时，用同一套指标在私有语料上再跑一遍。
  - **④ 跨语言检索未纳入**：题目与语料同语言（中文题打中文语料、英文题打英文语料）。
    中文提问打英文文档需要多语言 embedding，属于另一条链路，单独评测。
  - **⑤ 融合策略只测了一种**：当前只测「归一化加权」，未测 RRF / 重排（Reranker 已在 RAG 链路里，
    但没有用于评测对照）。触发条件：换真实 embedding 模型后混合仍不优于 BM25，就该把 RRF 纳入对照。

### D-18 切分器：抽端口、接框架、默认仍留在自研

- **决策**：新增 `TextSplitter` 端口，`rag.splitter=self`（默认，自研标题感知切分）或 `langchain4j`
  （`DocumentSplitters.recursive` 薄适配）；两种实现产出同一个 DTO，配置切换，默认不换。
- **为什么要抽端口**：切分是检索质量最敏感的一环，而项目里一直只有一种实现、一组凭经验定的参数
  （800/120）——「自研切分够不够好」是个从没被验证过的假设。抽端口不是为了将来可能有别的实现，
  而是为了**现在就能拿框架实现当参照物**：没有第二个实现，就没有对照。
- **为什么接框架而不是自己再写一个更好的**：框架实现零维护、与 `EmbeddingStoreIngestor` 天然同源，
  而且它是「别人做了十年、被大量项目用过」的默认答案 —— 拿它对标自研，结论才有说服力。
  适配层刻意只做 `Document`/`TextSegment` 映射，不掺任何自研逻辑，否则对照就变成了「框架+自研 vs 自研」。
- **为什么默认仍是自研（实测，不是偏好）**：文档级 Hit@5 打平（0.885 : 0.885），但自研在 Hit@1
  （0.808 vs 0.769）与 MRR（0.846 vs 0.827）更好；更关键的是框架切分不认识 Markdown 标题，
  片段没有标题路径 —— 引用只能落到「文档 + 片段序号」，而「引用定位到小节」是本项目 RAG 的
  可解释性卖点（D-14）。框架版省下的只是 19 个片段（152 vs 171）的体积。
- **什么时候该切过去**：a) 知识库以非 Markdown 为主（PDF/Word 抽出的文本本来就没有标题结构，
  自研的标题感知失去用武之地）；b) 引用定位不再需要到小节级别；c) 准备用 `EmbeddingStoreIngestor`
  把「切分 → 向量化 → 落库」整条交给框架 —— 那时为链路同源，切分跟着走框架更自然。
- **已知遗留**：
  - ① 只用了字符口径的 `recursive(maxChars, overlap)`；LC4j 还提供 token 口径
    （`recursive(sizeInTokens, overlapInTokens, TokenCountEstimator)`），没试过 —— 触发条件：出现
    「同长度片段里中英文信息量差太多」的检索问题（token 口径才能让中英片段长度可比）。
  - ② 切分粒度（800/120）本身没做过敏感性扫描：现在的评测只能说明「两种切分打平」，
    不能说明「800/120 是最优」。触发条件：真实语料（用户上传文档）超过 300 篇时，把
    `maxChars ∈ {400, 800, 1200}` 一起扫一遍。

### D-19 生产写入路径不接 EmbeddingStoreIngestor（读了实现之后否决）

- **决策**：保留自研写入路径（切分 → 批量向量化 → MySQL 片段行 + 向量库显式 id 落库）；
  框架的 `EmbeddingStoreIngestor` 只用在评测里的 naive RAG 基线（2.19）。
- **依据是字节码，不是文档措辞**：`EmbeddingStoreIngestor.ingest()` 调的是
  `EmbeddingStore.addAll(List<Embedding>, List<TextSegment>)` —— 点 id 由 store 的 `generateIds()`
  生成；而我们的 `EmbeddingStoreRetrievalIndex` 调的是 `addAll(List<String> ids, ...)`，
  id = `nameUUIDFromBytes("documentId#chunkIndex")`。
- **为什么这个差别重要**（两个不变量）：
  ① **重复灌库必须是覆盖，不是新增** —— 同一份文档重新索引时（内容改了、模型换了），
  稳定 id 让写入天然幂等；换成自动 id 就得依赖「先按 document_id 删一遍」这种两步操作，
  中间失败会留下幽灵点。
  ② **向量库不是权威**（D-16）：它随时可以删掉重建，重新灌出来的点必须能和旧点对上，
  否则无法判断「这是不是同一条」；而权威是 MySQL 的片段表，Ingestor 根本不管那一侧。
- **换来什么**：生产写入路径继续持有两个不变量；代价是保留几十行自研胶水
  （批量循环 + 片段行构造 + 索引写入）—— 注意是几十行，不是上百行，所以「省胶水」在这里并不成立。
- **顺带否掉的另外两件事**：① 不用 Ingestor 的默认切分（它与生产配置不一致，换切分等于全库重灌）；
  ② 不把 `EmbeddingStoreContentRetriever` 当生产检索器（没有关键词通道与引用路径，片段级差距已经量出来了）。
- **什么时候该重新考虑**：如果哪天决定「向量库当唯一权威」（不再往 MySQL 片段表落文本与向量），
  自动点 id 就不再是问题，那时把整条 ingest 交给框架反而更省 —— 这是个架构选择，不是组件选择。

### D-20 真实向量跑完之后：先换融合策略，不调权重、不换模型

- **决策**：下一步做 RRF（rank fusion）对照，把当前「归一化加权融合」当作被检验的假设；
  暂不动融合权重，也不更换／升级向量模型。
- **证据（真实向量口径，文档级 Hit@1 / MRR）**：混合 0.692 / 0.795 ＜ BM25 0.769 / 0.844
  ＜ 框架纯向量 0.769 / 0.854；权重扫描 w ∈ [0.20, 0.80] 最好一档 0.731 / 0.827，
  **没有任何一档超过纯 BM25**；片段级 BM25 0.769 / 0.838 也是全面最好。
- **为什么不是「调权重」**：权重是两路之间的比例，而扫描已经把整个区间走完了都没有改善 ——
  说明病的不是比例，而是「怎么合」：两路分数一个来自余弦、一个来自 BM25，量纲不同，
  归一化到 [0,1] 再加权这种办法对分布形状很敏感（候选集一变，归一化的基准就变）。
  RRF 只用**排名**不用分数，天然免疫量纲问题，所以它是下一个该试的东西。
- **为什么不是「砍掉关键词通道、只留向量」**：真实向量在片段级全面落后（Hit@1 0.423 vs 0.769、
  MRR 0.607 vs 0.838）。而片段级恰恰是拼进 prompt 的那一档 —— 文档对了、片段不对，模型还是看不到答案。
- **为什么不是「换更好的向量模型」**：模型已经证明不是瓶颈（它让向量通道从 0.846 涨到 0.962）。
  继续在模型上加钱，换不来「混合赢单路」这件事。
- **验收标准（先说好，免得事后找解释）**：RRF 必须在**文档级 Hit@1 与 MRR 上同时超过纯 BM25
  （0.769 / 0.844）**，才算「融合策略修好了」；否则就接受更简单的方案 ——
  关键词通道优先 + 向量兜底，把复杂度从融合里拿掉。
