# ai-ilink · 多 Bot 微信智能助手

一个基于 Spring Boot 的微信多机器人平台，核心是一个**自研的 Function Calling Agent 运行时**：支持多微信账号独立扫码登录、自然语言对话、工具调用、多模态交互（文本 / 图片 / 语音 / 文件），以及 Word / Excel / PDF / 简历等办公文档的生成与处理。

## 核心亮点

- **多 Bot 生命周期管理**：每个 Bot 独立线程池，支持扫码登录、免扫码恢复、断线重连、优雅关闭；以 SDK 认证结果为唯一身份信任根
- **自研 Function Calling 编排引擎**：迭代式工具调用循环，带领域路由、死循环保护、tool 消息对清洗、Watcher 兜底
- **可插拔工具系统**：策略模式 + Spring 自动装配，8 个工具零侵入扩展（天气 / Word / Excel / PDF / 简历 / 文生图 / 企业查询 / 行业新闻）
- **上下文持久化**：Redis 缓存 + MySQL 双写，跨轮记忆与文件路径持久化
- **多模态链路**：图片理解、语音识别（STT）、语音合成（TTS）、文件解析（Tika）
- **RAG 检索链路**：内置知识资产与用户上传文档统一入知识库 —— 标题感知切分 +（向量 ∥ BM25）混合召回 + 可选精排，返回带来源引用的上下文；`search_knowledge` 工具让模型按需翻资料，而不是把资料全文塞进 prompt

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 / 框架 | Java 17、Spring Boot 4.1 |
| 持久化 | MySQL 8 + MyBatis（数据层）、Redis 7（缓存层） |
| 大模型 | DeepSeek（OpenAI 兼容 Chat Completions API） |
| 多模态 | 阿里百炼 DashScope（文生图 / STT / TTS）、视觉模型 |
| 文档处理 | Apache POI（Word / Excel）、Apache Tika（文本提取）、LibreOffice（Word→PDF） |
| 数据服务 | 高德天气、天眼查、Metaso 联网搜索 |
| 检索（RAG） | LangChain4j（EmbeddingModel / ScoringModel 抽象）、DashScope text-embedding-v4（向量）、gte-rerank（精排，可选） |
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

### 前置条件

- JDK 17+
- Maven 3.9+
- MySQL 8.4+ 与 Redis 7+：本机安装，或用仓库根目录的 `docker-compose.yml` 一键启动（`docker compose up -d`）
- GitHub Packages Token：SDK 依赖 `wechat-ilink-sdk` 托管在 GitHub Packages，首次构建需在 `~/.m2/settings.xml` 配置凭据：

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>你的GitHub用户名</username>
      <password>你的GITHUB_TOKEN（需 read:packages 权限）</password>
    </server>
  </servers>
</settings>
```

- LibreOffice（可选）：`word_to_pdf` 工具需要，通过 `libreoffice.path` 配置可执行文件路径

首次运行前创建数据库（或使用 docker-compose 自动创建）：

```sql
CREATE DATABASE ai_ilink CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 环境变量

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

### 构建与运行

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

## Roadmap

- [x] 数据层迁移：MySQL（持久化）+ Redis（缓存）
- [x] Context Manager：摘要压缩 + 长期记忆（v2.4）
- [x] RAG 文档知识库：文件入库 → 混合检索 → 带引用回答（v2.6）
- [x] 单元测试覆盖核心链路（FC 编排 / 路由 / 历史缓存 / 记忆 / RAG，共 79 个）
- [x] CI：GitHub Actions 起 MySQL + Redis 服务容器跑 `mvn test`
- [ ] MCP 客户端接入，连接外部工具生态
- [ ] 长期记忆复用检索基建（语义召回替代 dimension 精确匹配）
- [ ] 应用容器化部署（Dockerfile + compose 一体化）

## 致谢

- [wechat-ilink-sdk](https://github.com/lith0924) —— 微信消息通道 SDK
- [openai-resume-builder](https://github.com/openai/openai-resume-builder) —— 简历生成方法论与模板（MIT License，经适配集成，详见 [resume-builder/README.md](src/main/resources/resume-builder/README.md)）
- DeepSeek / 阿里百炼 DashScope / 天眼查 / Metaso / 高德开放平台

## License

TODO：决定开源协议后填写。
