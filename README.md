# ai-ilink · 多 Bot 微信智能助手

[![CI](https://github.com/RUO-rr/wechat_ai_ilink/actions/workflows/ci.yml/badge.svg)](https://github.com/RUO-rr/wechat_ai_ilink/actions/workflows/ci.yml)

一个基于 Spring Boot 的微信多机器人平台，核心是一个**自研的 Function Calling Agent 运行时**：支持多微信账号独立扫码登录、自然语言对话、工具调用、多模态交互（文本 / 图片 / 语音 / 文件），以及 Word / Excel / PDF / 简历等办公文档的生成与处理。

## 核心亮点

- **多 Bot 生命周期管理**：每个 Bot 独立线程池，支持扫码登录、免扫码恢复、断线重连、优雅关闭；以 SDK 认证结果为唯一身份信任根
- **自研 Function Calling 编排引擎**：迭代式工具调用循环，带领域路由、死循环保护、tool 消息对清洗、Watcher 兜底
- **可插拔工具系统**：策略模式 + Spring 自动装配，8 个工具零侵入扩展（天气 / Word / Excel / PDF / 简历 / 文生图 / 企业查询 / 行业新闻）
- **上下文持久化**：Redis 缓存 + MySQL 双写，跨轮记忆与文件路径持久化
- **多模态链路**：图片理解、语音识别（STT）、语音合成（TTS）、文件解析（Tika）
- **RAG 检索链路**：内置知识资产与用户上传文档统一入知识库 —— 标题感知切分 +（向量 ∥ BM25）混合召回 + 可选精排，返回带来源引用的上下文；`search_knowledge` 工具让模型按需翻资料，而不是把资料全文塞进 prompt
- **长期记忆语义召回**：记忆读路径复用同一套检索基建 —— 按与当前问题的相关性召回，而不是只取最近 N 条；写路径用余弦相似度做重复兜底，冲突解决仍是 LLM 判定 + supersede 链（可审计）
- **向量通道可换存储**：向量落点收成 `RetrievalIndex` 端口，默认进程内，可切换到 Qdrant（HNSW）——切换前后上层检索与融合逻辑不变；默认值由实测决定（见「向量库」章节）

## 面试官 5 分钟（先看这些）

时间有限时按这个顺序看，能最快判断这个项目值不值得聊：

1. **[ARCHITECTURE.md](ARCHITECTURE.md)「一、架构演进路线」**（v2.0 → v2.15）：每段都是「问题 → 候选 → 做法 → 结论」，包含被**否掉**的方案
2. **[docs/bench/](docs/bench/)**：三份实测报告 —— 向量库选型基准（10 万片段 p50/p95 + recall）、检索评测（36 题 × 2 个向量口径 × 6 条策略）、端到端问答 transcript（问题 → 检索命中 → 回答 → 出处）
3. **[ARCHITECTURE.md](ARCHITECTURE.md)「五、决策记录」**（D-01 ~ D-23）：每条写清「为什么要」与「为什么不要替代方案」——包括**主动否决自己做了半天的方案**（D-19 读了字节码后否决 `EmbeddingStoreIngestor`）
4. **跑一遍**：`mvn -B test`（142 个单测，覆盖 FC 编排 / 检索 / 记忆 / 向量库端口 / 端到端问答；无 key 的网络用例自动跳过）
5. **一键起服务**：见「快速开始 → Docker 一键启动」

一句话概括取法：**能测的都测，不能测的写清边界** —— 每个默认值（向量库开不开、精排开不开、融合用哪种、切分用哪种）背后都有一组数字或一段被记录下来的否决理由。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 / 框架 | Java 17、Spring Boot 4.1 |
| 持久化 | MySQL 8 + MyBatis（数据层）、Redis 7（缓存层） |
| 大模型 | DeepSeek（OpenAI 兼容 Chat Completions API） |
| 多模态 | 阿里百炼 DashScope（文生图 / STT / TTS）、视觉模型 |
| 文档处理 | Apache POI（Word / Excel）、Apache Tika（文本提取）、LibreOffice（Word→PDF） |
| 数据服务 | 高德天气、天眼查、Metaso 联网搜索 |
| 检索（RAG） | LangChain4j（EmbeddingModel / ScoringModel / DocumentSplitter 抽象）、DashScope text-embedding-v4（向量）、gte-rerank（精排，可选） |
| 向量库（可选） | Qdrant 1.19（HNSW，经 LangChain4j EmbeddingStore 接入；默认不启用） |
| 消息通道 | wechat-ilink-sdk（GitHub Packages） |

## 架构概览

```
微信 SDK (wechat-ilink-sdk)
        │  消息推送
        ▼
MainController ──→ IntentDetection ──→ 文本 / 画图 / 语音 / 文件
        │
        ▼
BotManager（多 Bot 生命周期 + 身份管理）
        │
        ▼
FunctionCallingOrchestrator（迭代式 FC 循环）
        │
        ├── ToolRouter（领域路由 → 工具子集）
        ├── KnowledgeRetriever（向量 + BM25 混合召回 → 带引用上下文）
        ├── MemoryRetriever（长期记忆语义召回：最近 N 条保底 + 相关性补足）
        ├── ToolRegistry（自动装配 8 个工具）
        └── ConversationHistory（Redis 缓存 + MySQL 双写）
```

详细的架构演进与技术决策见 [ARCHITECTURE.md](ARCHITECTURE.md)，消息时序见 [project-flow.mermaid](project-flow.mermaid)。

## 目录结构

```
src/main/java/io/github/wangyangxu/ailink/
├── client/     # LLM / Metaso / 天眼查 HTTP 客户端
├── config/     # 全局配置（Bot、RestTemplate）
├── controller/ # 消息入口 + Bot 管理 REST API
├── mapper/     # MyBatis Mapper 接口
├── memory/     # 长期记忆检索（按用户隔离的混合索引 + 相关性召回）
├── model/      # 领域模型（BotInstance、ChatMessage...）
├── rag/        # RAG 检索链路（切分 / 向量化 / 混合索引 / 混合检索）
├── service/    # 核心服务（BotManager / FC 编排 / 对话历史 / 多模态...）
├── tool/       # 工具系统（ToolDefinition + 8 个实现）
└── util/       # 工具类
src/main/resources/
├── mapper/               # MyBatis XML
├── resume-builder/       # 简历生成方法论与模板
├── static/index.html     # Bot 管理面板
├── schema.sql            # 建表脚本
└── application.properties # 全部配置
```

## 快速开始

### Docker 一键启动（推荐先走这条）

```bash
cp .env.example .env      # 至少填 LLM_API_KEY；RAG_EMBEDDING_API_KEY 不填则 RAG 降级为本地词法向量
docker compose up -d --build
```

起来之后：

| 用途 | 命令 / 地址 |
|---|---|
| 管理页面 | <http://localhost:8080>（`/api/**` 需要 `X-API-Token` 头，值 = `.env` 里的 `MANAGEMENT_API_TOKEN`） |
| 健康检查 | `curl -H "X-API-Token: change-me-in-production" http://localhost:8080/api/health` |
| 看日志 | `docker compose logs -f app` |
| 停掉 | `docker compose down`（数据在命名卷里，不会丢） |

- **表结构自动创建**：应用启动时执行 `src/main/resources/schema.sql`（`spring.sql.init.mode=always`），首次启动不需要手动跑 SQL
- **依赖顺序有保障**：`app` 等 MySQL / Redis 的 healthcheck 通过后才启动，容器 healthcheck 打的是 `/api/health`
- **镜像构建由 CI 验证**：本项目的开发机没装 Docker，所以 `Dockerfile` 的正确性交给 `.github/workflows/ci.yml` 里的 `docker-build` 作业（每次 push 到 main 构建一次，不推送镜像）

### 本机运行（开发用）

#### 前置条件

- JDK 17+、Maven 3.9+
- MySQL 8.4+ 与 Redis 7+：本机安装，或用仓库根目录的 `docker-compose.yml` 起依赖（`docker compose up -d mysql redis`）
- **依赖无需额外凭据**：`wechat-ilink-sdk` 已发布到 Maven Central（`io.github.lith0924:wechat-ilink-sdk`），全新环境直接 `mvn package` 即可。
  `pom.xml` 里仍声明了作者的 GitHub Packages 仓库；没配 token 时 Maven 会尝试它并在日志里留一条 401 警告，随后从 Central 拿到依赖，**可以忽略**。
  只有当你确实要从 GitHub Packages 拉取时，才需要在 `~/.m2/settings.xml` 里补 `github` server 的 `read:packages` 凭据
- LibreOffice（可选）：`word_to_pdf` 工具需要，通过 `libreoffice.path` 配置可执行文件路径

数据库本身需要存在（用上面的 compose 起 MySQL 时它会自动创建 `ai_ilink`）；**表结构不用手动建** ——
应用启动时会执行 `src/main/resources/schema.sql`（`spring.sql.init.mode=always`，脚本里全是 `CREATE TABLE IF NOT EXISTS`）。
自建 MySQL 时先执行：

```sql
CREATE DATABASE ai_ilink CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

#### 环境变量

所有密钥通过环境变量注入，不写入仓库：

| 变量 | 用途 | 必填 |
|------|------|------|
| `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` | 文本对话 + Function Calling | 是 |
| `LLM_VISION_BASE_URL` / `LLM_VISION_MODEL` / `LLM_VISION_API_KEY` | 图片理解 | 否 |
| `LLM_DRAW_BASE_URL` / `LLM_DRAW_MODEL` / `LLM_DRAW_API_KEY` | 文生图 | 否 |
| `LLM_TTS_BASE_URL` / `LLM_TTS_MODEL` / `LLM_TTS_API_KEY` | 语音合成 | 否 |
| `LLM_STT_API_KEY` | 语音识别 | 否 |
| `WEATHER_API_KEY` / `WEATHER_BASE_URL` | 高德天气 | 否 |
| `LLM_SEARCH_KEY` | Metaso 联网搜索 | 否 |
| `TIANYANCHA_API_KEY` | 天眼查企业信息 | 否 |
| `RAG_EMBEDDING_API_KEY` | RAG 向量模型（DashScope text-embedding-v4），缺省复用 `LLM_STT_API_KEY`；都没有时降级为本地词法向量 | 否 |
| `RAG_RERANK_ENABLED` | 是否开启 gte-rerank 精排（默认 false） | 否 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | MySQL 账号密码 | 是 |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | MySQL 地址 / 端口 / 库名（默认 localhost:3306/ai_ilink） | 否 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 地址 / 端口 / 密码（默认 localhost:6379/无） | 否 |

#### 构建与运行

```bash
mvn clean package
```

```powershell
# PowerShell 示例
$env:LLM_BASE_URL = "https://api.deepseek.com"
$env:LLM_MODEL    = "deepseek-chat"
$env:LLM_API_KEY  = "sk-xxx"
$env:MYSQL_USER   = "ai_ilink"
$env:MYSQL_PASSWORD = "ai_ilink123"
mvn spring-boot:run
```

启动后打开 `http://localhost:8080` 进入管理面板，创建 Bot 并扫码登录，即可在微信中与机器人对话。

> TODO：补充内网穿透（ngrok）接入说明与演示截图。

## RAG 检索链路

```
内置知识资产 resume-builder/**  ┐
用户上传文件 data/documents/**  ┴─→ 读取 → SHA-256 指纹去重 → 标题感知切分 → 批量向量化 → 落库 → 内存索引
                                                                            （向量化失败只降级，不阻断写路径）

提问 ─→ 查询向量化 ─┬─ 向量召回（余弦）
                    └─ 关键词召回（BM25）
                           ↓ 两路分数各自归一化后加权融合（默认 0.65 : 0.35）
                        候选融合去重（按 文档#片段）
                           ↓ 可选
                        精排（gte-rerank 逐对打分）
                           ↓
                        单文档配额 + 字符预算 → 带「来源文件 + 章节 + 相关性」的上下文
```

- **两个接入点**：① `search_knowledge` 工具（领域 `general`，任何路由分支都会带上），模型自己决定何时检索；
  ② `ChatFileService` 收到文件后全文入库，摘要改为「覆盖率采样」（首片段取开头、尾片段取结尾），不再只截前 2000 字
- **四层降级**：向量模型不可用 → 查询只走关键词；写入时向量化失败 → 片段仍入库（`embedding` 为 NULL）；
  精排不可用 → 按召回分排序；知识库为空 → 明确告诉模型「不要编造引用」
- **向量空间隔离**：片段记录 `embedding_model`，检索只比较同模型向量；本地词法哈希向量（无密钥 / CI 场景）
  与 DashScope 向量互不污染，「换模型 = 重建索引」由 `content_hash + embedding_model` 共同决定
- **自检接口**：`GET /api/knowledge` 返回文档数 / 片段数 / 向量化比例 / 当前向量模型；
  `GET /api/metrics` 增加 `ragRetrievals`、`ragAvgMs`、`ragEmptyResults`
- **可调参数**：`rag.chunk.*`（切分粒度与重叠）、`rag.retrieve.*`（返回条数 / 候选倍数 / 单文档配额）、
  `rag.fuse.vector-weight`（语义与字面权重）、`rag.context.max-chars`（上下文预算）

## 长期记忆检索（v2.7）

```
用户消息 ─→ 记忆召回 ─┬─ 保底：最近 N 条（全局偏好不会因为本轮没命中而消失）
                      └─ 相关：向量 ∥ BM25 混合召回，补足到槽位上限
                             ↓ 与知识库共用 HybridIndex / HybridFusion
                      记忆槽 ≤5（fact / preference）    笔记槽 ≤3（note）
                             ↓
                与摘要槽（1 条，固定槽位、不参与检索）一起注入 system 消息
```

- **解决什么问题**：旧读路径按 id 倒序取最近 5 条 —— 用户记忆一多，近期但与当前问题无关的记忆会挤掉真正相关的那条（「我上次说的那个项目」命中不了）
- **为什么要保底配额**：「回答要简洁」这类全局偏好与任何具体问题都不相似，纯相关性召回会让它在没命中的轮次里消失；「保底 + 相关」各留一部分配额，既稳定又不丢信息
- **降级**：检索关闭 / 索引装载失败 / 向量模型不可用 → 自动退回纯 recency 注入，回复不会因为检索失败而中断
- **写入去重**：LLM 判定 new 时先用余弦相似度查一遍同用户已有记忆（阈值 0.92）——answer_style / reply_style 这种同义 dimension 写法不同也能拦住重复；supersede 是 LLM 看过旧记忆后的显式判断，不干预
- **按需装载**：某用户第一次被访问时才从库里拉他最近的 active 记忆建索引（每人几十条，一次批量向量化），不预热全量、不拖慢启动
- **可观测与可调**：/api/metrics 增加 memoryRecalls / memoryRecallAvgMs / memoryRecallEmpty；参数 memory.recall.*（融合权重 / 候选倍数 / 保底条数）、memory.dedupe.*（开关与阈值）、memory.index.max-entries-per-user

## 向量库（v2.8，默认关闭）

```
向量通道 = RetrievalIndex 端口（写入 / 读取 / 统计收口）
  ├─ in-memory（默认）：向量与关键词都在堆内，零外部依赖
  └─ qdrant：向量落 Qdrant HNSW（经 LangChain4j EmbeddingStore），关键词通道仍在堆内
                 ↓ 上层混合召回 / 融合 / 降级不知道 provider 是哪一种
```

- **为什么默认不开（实测）**：合成语料十万片段下，向量通道 Qdrant 快 8 倍（p50 3.80ms vs 31.00ms），
  但**端到端融合只快 14%**（199.50ms vs 231.06ms，瓶颈在内存 BM25），p95 反而更差；
  同时建索引慢 2.9 倍（11.4s vs 3.2s），recall@10 从 1.000（精确解）降到 0.692（近似解）。真实语料只有几百个片段，
  引入向量库是净亏 —— 所以默认留在进程内，Qdrant 作为可切换 provider 保留
- **怎么开**：起一个 Qdrant（默认 127.0.0.1:6334 gRPC）→ `vectorstore.provider=qdrant`，
  其余看 `vectorstore.qdrant.*`（collection / dimension / api-key / tls / 超时与退避）；
  Qdrant 连不上会降级为进程内并告警，不会影响启动
- **回灌与幂等**：点 id 由 `UUID v3(文档#片段)` 派生，集合为空时启动自动从库内片段回灌（分批写入），
  重复回灌是覆盖不是新增
- **降级**：远端不可用 → 记一次告警并进入退避窗口，窗口内检索自动只走关键词通道
- **复现基准**：`mvn -B test -Dtest=VectorStoreBenchmarkTest -Dbench.enabled=true -DargLine=-Xmx3g -Dbench.sizes=10000,100000 -Dbench.dim=256`
  （产出 `target/bench/vector-store-benchmark.md`；一次实跑的完整报告见 [docs/bench/vector-store-benchmark.md](docs/bench/vector-store-benchmark.md)）

## 检索评测（v2.9）

```
rag-eval 语料：33 篇公开文档 / 171 个片段 / 26 道标注题
        ↓  与生产同样的切分（标题感知，maxChars=800 / overlap=120）
   三通道同口径对照：向量余弦 ∥ BM25 ∥ 混合（0.65 : 0.35）
        ↓  Hit@1 / Hit@5 / MRR@5，文档级 + 片段级各一套
   完整实跑报告：docs/bench/rag-eval.md
```

- **要回答的问题**：「混合检索比单路好多少」不再是口号 —— 每次都能用同一套语料与题目量出来，
  后续换模型、调权重、改切分参数都有回归锚点
- **实测结论（离线词法向量，文档级）**：Hit@5 —— BM25 0.962 > 混合 0.885 > 向量 0.846；
  Hit@1 —— 混合 0.808 最好。离线哈希向量与 BM25 吃同一批 token，混合赢在头部排序、
  输在尾部召回被向量稀释；权重扫描 w ∈ [0.20, 0.80] 曲线几乎不动（取舍与边界见 D-17）
- **标注即校验**：`questions.tsv` 每题的「答案字面串」必须真出现在期望文档的片段里，装载时逐条自检，
  标注写错先失败 —— 不让「标注错了」记成「检索不行」
- **切分器对照**：自研标题感知切分 vs LangChain4j `DocumentSplitters.recursive`（同语料、同题库、同混合通道）——
  文档级 Hit@5 打平（0.885 : 0.885），Hit@1 与 MRR 自研更好（0.808 / 0.846 vs 0.769 / 0.827），
  且只有自研版带标题路径；`rag.splitter=self|langchain4j` 一行配置切换（选型理由见 D-18）
- **框架原生基线**：`Document` → `EmbeddingStoreIngestor` → `EmbeddingStoreContentRetriever`（向量单路）
  跑同一批题 —— 文档级 Hit@5 打平（0.885），但片段级自研明显领先（Hit@1 0.769 vs 0.577）；
  差距出在关键词通道「把答案那一片顶上来」这件事上（见 2.19 / D-19）
- **真实向量模型复跑**（`-Drag.eval.model=dashscope`，需要 `RAG_EMBEDDING_API_KEY`）：
  换成 DashScope `text-embedding-v4`（1024 维）后，向量通道文档级 Hit@5 从 0.846 涨到 0.962 ——
  离线口径确实低估了向量；**但混合仍然输给纯 BM25**（文档级 Hit@1 0.692 vs 0.769），
  权重扫描也没有任何一档能赢 → 瓶颈在融合策略，不在模型（见 2.20 / D-20）
- **融合策略对照 + 题目风格分组**（v2.13）：题库扩到 36 题（26 道原文措辞 + 10 道口语改写，成对）。
  真实模型口径下 RRF 召回最好（文档 Hit@5 0.972、片段 0.944，改写组 10/10 进 top-5），
  但排头不如 BM25（Hit@1 0.611 vs 0.667）→ 未通过 D-20 验收，改为「RRF 召回 + 重排」路线（D-21）；
  改写组里**只有真实语义向量在排头上反超 BM25**（0.500 vs 0.400，离线向量同组仅 0.200）
- **精排接入**（v2.14，`-Drag.eval.rerank=dashscope`）：接上 gte-rerank-v2 后**通过 D-21 验收** ——
  文档级 Hit@1 0.611 → **0.778**、MRR 0.729 → **0.851**、Hit@5 拉满 **1.000**（36 题全进前 5）；
  且精排后「加权融合」与「RRF」成绩完全相同 → 融合管召回、精排管排序，生产默认融合不必改。
  开启方式：`RAG_RERANK_ENABLED=true`（默认关闭，理由见 D-22）
- **精排默认已开（v2.15）**：72 次真实调用实测 **p50 153ms / p95 206ms / max 272ms** ——
  一百多毫秒换文档级 Hit@1 翻倍，账算得过来，所以 `rag.rerank.enabled` 默认改为 true
  （缺 key / 模型不可用自动降级为不精排）
- **端到端问答**（v2.15）：`AiServices + ContentRetriever` 两条链路（框架原生 / 自研检索适配）跑通，
  问题 → 检索命中 → 回答 → 出处清单全部留档：[docs/bench/rag-assistant-demo.md](docs/bench/rag-assistant-demo.md)。
  出处清单由代码从检索结果拼出，不交给模型（实测模型会编路径）；生产 top-4 下答案片段命中 2/3，
  印证片段级仍是短板
- **怎么跑**：`mvn -B test -Dtest=RagEvaluationTest`
  （产出 `target/bench/rag-eval.md`；`-Drag.eval.vectorWeight=` 可覆盖融合权重）

## 技术决策清单（最近 8 条，完整版在 ARCHITECTURE.md）

| 决策 | 结论 | 依据 |
|---|---|---|
| D-16 向量库 | 默认留在进程内，Qdrant 作为可切换 provider | 十万片段向量通道快 8 倍，但端到端融合只快 14%、p95 更差、recall@10 从 1.000 掉到 0.692 |
| D-17 检索评测 | 建评测集 + 分组报数，把「混合更好」从口号变成数字 | [docs/bench/rag-eval.md](docs/bench/rag-eval.md) |
| D-18 切分器 | 抽 `TextSplitter` 端口，默认仍用自研标题感知切分 | 与 LangChain4j recursive 打平（Hit@5 0.885），但自研带标题路径、排序更好 |
| D-19 Ingestor | 生产写入路径不接 `EmbeddingStoreIngestor` | 读字节码：它用自动生成的点 id，与「重复灌库幂等覆盖 + MySQL 是权威」冲突 |
| D-20 融合策略 | 先换策略而不是调权重 | 权重扫描 w ∈ [0.20, 0.80] 没有一档能赢纯 BM25 |
| D-21 RRF | 保留为召回策略，不设默认 | 赢召回（文档 Hit@5 0.972）、输排头（Hit@1 0.611 vs 0.667） |
| D-22 精排 | 接上并量化，随后默认开启 | 文档级 Hit@1 0.611 → 0.778、Hit@5 → 1.000；代价 p50 153ms / p95 206ms |
| D-23 端到端问答 | 只做集成验证，不动生产回答路径 | 换回答路径属于产品行为变更，而回答质量还没有评测口径 |

## Roadmap

- [x] 数据层迁移：MySQL（持久化）+ Redis（缓存）
- [x] Context Manager：摘要压缩 + 长期记忆（v2.4）
- [x] RAG 文档知识库：文件入库 → 混合检索 → 带引用回答（v2.6）
- [x] 单元测试覆盖核心链路（FC 编排 / 路由 / 历史缓存 / 记忆 / RAG / 记忆检索 / 向量库端口 / 检索评测 / 切分器 / 融合与精排 / 端到端问答，共 142 个）
- [x] CI：GitHub Actions 起 MySQL + Redis 服务容器跑 `mvn test`
- [ ] MCP 客户端接入，连接外部工具生态
- [x] 长期记忆复用检索基建：语义召回替代「只取最近 N 条」（v2.7）
- [x] 向量库可选接入：RetrievalIndex 端口 + Qdrant 实现（v2.8，实测后默认仍为进程内）
- [x] 检索质量评测：33 篇公开文档 + 26 题的「向量 / BM25 / 混合」三通道对照（v2.9）
- [x] LangChain4j 原生切分器接入与对照：`TextSplitter` 端口 + `rag.splitter` 切换（v2.10，P1 第一刀）
- [x] LangChain4j 原生链路基线：`EmbeddingStoreIngestor` + `EmbeddingStoreContentRetriever` 搭 naive RAG，与自研同口径对照（v2.11，P1；生产写入路径按 D-19 保留自研）
- [x] 用 `AiServices` + `ContentRetriever` 串端到端问答（框架原生 / 自研检索适配两条链路，需 key，无 key 自动跳过）；记录见 `docs/bench/rag-assistant-demo.md`（v2.15，见 2.23 / D-23）
- [ ] 【面试前必补】补成对改写题（同一事实两种问法）并分组报数，把「题库偏置」和「模型能力不足」分开
      —— 现在的 26 题都是词面重叠型，等于在 BM25 的主场比（详见 D-17 遗留 ①）
- [x] 用真实 embedding 模型复跑检索评测（DashScope `text-embedding-v4`）：向量通道确实被离线口径低估，但混合仍输 BM25（v2.12，见 2.20）
- [x] RRF 融合对照 + 题库扩到 36 题（+10 道口语改写，按 cohort 分组报数）：RRF 赢召回、输排头，未通过验收（v2.13，见 2.21 / D-21）
- [x] RRF 召回 + 精排：升级 dashscope-sdk 到 2.22.30 打通 gte-rerank-v2，**通过 D-21 验收**（文档 Hit@1 0.778、MRR 0.851、Hit@5 1.000，v2.14，见 2.22 / D-22）
- [ ] 片段级精度：文档级已被精排拉满（Hit@5 1.000），但片段级 Hit@1 仍 0.611；验收标准 = 片段级 Hit@1 超过 0.70（D-22）
- [ ] 应用容器化部署（Dockerfile + compose 一体化）

## 致谢

- [wechat-ilink-sdk](https://github.com/lith0924) —— 微信消息通道 SDK
- [openai-resume-builder](https://github.com/openai/openai-resume-builder) —— 简历生成方法论与模板（MIT License，经适配集成，详见 [resume-builder/README.md](src/main/resources/resume-builder/README.md)）
- DeepSeek / 阿里百炼 DashScope / 天眼查 / Metaso / 高德开放平台

## License

TODO：决定开源协议后填写。
