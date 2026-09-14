# RAG 检索评测：混合 vs 单路

由 `RagEvaluationTest` 生成。向量模型：`local-hashing-v1`（512 维），三个通道吃同一份语料、同一批问题。

- 语料 171 个片段（公开文档，见 `sources.tsv`）
- 题目 36 条，人工标注「期望文档 + 答案里的字面串」
- 切分与生产一致：maxChars=800 / overlap=120（主表用自研标题感知切分，对照见「切分器对照」一节）；候选 = topK × 3，融合权重 0.65 : 0.35
- 指标：Hit@1 / Hit@5 / MRR@5；「文档级」= 命中来源文档，「片段级」= 命中含标准答案的片段

## 总览

| 通道 | 文档级 Hit@1 | 文档级 Hit@5 | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@5 | 片段级 MRR |
|---|---|---|---|---|---|---|
| 向量余弦 | 0.556 | 0.806 | 0.661 | 0.417 | 0.667 | 0.522 |
| 关键词 BM25 | 0.667 | 0.944 | 0.774 | 0.611 | 0.889 | 0.707 |
| 混合 0.65 : 0.35 | 0.667 | 0.861 | 0.746 | 0.611 | 0.778 | 0.681 |
| 融合 RRF (k=60) | 0.611 | 0.861 | 0.722 | 0.556 | 0.778 | 0.653 |

## 融合权重敏感性（同一批问题，只改向量权重 w，关键词权重 = 1 − w）

| 向量权重 w | 文档级 Hit@1 | 文档级 Hit@5 | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@5 | 片段级 MRR |
|---|---|---|---|---|---|---|
| 0.20 | 0.667 | 0.889 | 0.753 | 0.611 | 0.833 | 0.695 |
| 0.35 | 0.667 | 0.889 | 0.753 | 0.611 | 0.833 | 0.692 |
| 0.50 | 0.667 | 0.861 | 0.748 | 0.611 | 0.778 | 0.683 |
| 0.65 | 0.667 | 0.861 | 0.746 | 0.611 | 0.778 | 0.681 |
| 0.80 | 0.611 | 0.833 | 0.697 | 0.556 | 0.750 | 0.632 |

## 切分器对照（同一份语料与题目，走混合通道）

| 切分策略 | 片段数 | 片段均长 | 文档级 Hit@1 | 文档级 Hit@5 | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@5 | 片段级 MRR |
|---|---|---|---|---|---|---|---|---|
| 自研标题感知切分 | 171 | 548 | 0.667 | 0.861 | 0.746 | 0.611 | 0.778 | 0.681 |
| LangChain4j DocumentSplitters.recursive | 152 | 579 | 0.639 | 0.861 | 0.738 | 0.583 | 0.778 | 0.662 |
- 自研切分器先按 Markdown 标题切小节、再降级拆分，片段带标题路径（引用能定位到小节）；
  LangChain4j 的 `DocumentSplitters.recursive` 只看段落/句子/字符长度，片段没有标题路径。
- 两者产出同一个 DTO，`rag.splitter=self|langchain4j` 切换，检索与融合逻辑一行都不用改。

## 框架原生 naive RAG 基线（LangChain4j Ingestor + ContentRetriever）

| 链路 | 片段数 | 文档级 Hit@1 | 文档级 Hit@5 | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@5 | 片段级 MRR |
|---|---|---|---|---|---|---|---|---|
| 框架原生 naive RAG（Ingestor + ContentRetriever，向量单路） | 152 | 0.611 | 0.833 | 0.702 | 0.444 | 0.722 | 0.548 |
| 自研链路（混合召回 + 引用拼装） | 171 | 0.667 | 0.861 | 0.746 | 0.611 | 0.778 | 0.681 |
- 两条链路吃同一份语料、同一批 36 题、同一个离线向量模型（`local-hashing-v1`），差别只在链路本身：
  框架那条是 `Document` → `EmbeddingStoreIngestor`（切分→向量化→落库）→ `EmbeddingStoreContentRetriever`（向量单路 top-k）；自研那条多了关键词通道与片段级引用路径。
- 读法：框架链路没有关键词通道，在词面型题库上天然吃亏；它也不提供「哪一份文档的哪一节」这种引用定位。
  这也是生产写入路径没有换成 `EmbeddingStoreIngestor` 的原因 —— 它以自动生成的点 id 落库，
  而我们需要 `文档#片段` 派生的稳定点 id（重复灌库是覆盖不是新增），且 MySQL 才是权威数据源。

## 题目风格分组：原文措辞 vs 口语改写

同一批事实、两种问法。分组报数的目的：把「检索本身好不好」与「题目复用了文档措辞」分开 ——
只报一组数字时，结论有可能是题目风格选出来的。

### 原文措辞组（26 题）

| 通道 | 文档级 Hit@1 | 文档级 Hit@5 | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@5 | 片段级 MRR |
|---|---|---|---|---|---|---|
| 向量余弦 | 0.692 | 0.846 | 0.769 | 0.538 | 0.769 | 0.647 |
| 关键词 BM25 | 0.769 | 0.962 | 0.844 | 0.769 | 0.962 | 0.838 |
| 混合 0.65 : 0.35 | 0.808 | 0.885 | 0.846 | 0.769 | 0.885 | 0.827 |
| 融合 RRF (k=60) | 0.692 | 0.885 | 0.788 | 0.654 | 0.885 | 0.763 |

### 口语改写组（10 题）

| 通道 | 文档级 Hit@1 | 文档级 Hit@5 | 文档级 MRR | 片段级 Hit@1 | 片段级 Hit@5 | 片段级 MRR |
|---|---|---|---|---|---|---|
| 向量余弦 | 0.200 | 0.700 | 0.378 | 0.100 | 0.400 | 0.195 |
| 关键词 BM25 | 0.400 | 0.900 | 0.592 | 0.200 | 0.700 | 0.367 |
| 混合 0.65 : 0.35 | 0.300 | 0.800 | 0.487 | 0.200 | 0.500 | 0.303 |
| 融合 RRF (k=60) | 0.400 | 0.800 | 0.550 | 0.300 | 0.500 | 0.367 |

口语改写组相对原文措辞组的变化（负号 = 改写后变差）：

| 通道 | 文档级 Hit@1 | 文档级 MRR | 片段级 Hit@1 | 片段级 MRR |
|---|---|---|---|---|
| 向量余弦 | -0.492 | -0.391 | -0.438 | -0.452 |
| 关键词 BM25 | -0.369 | -0.253 | -0.569 | -0.471 |
| 混合 0.65 : 0.35 | -0.508 | -0.359 | -0.569 | -0.524 |
| 融合 RRF (k=60) | -0.292 | -0.238 | -0.354 | -0.396 |
- 读法：如果改写组三条通道一起掉，说明瓶颈在「词面之外的理解」而不在融合策略；
  如果只有关键词通道掉、向量通道稳住，那才是向量通道的价值被量出来。

## 逐题明细（片段级排名，✗ = top-5 未命中）

| 题目 | 期望文档 | 向量 | BM25 | 混合 |
|---|---|---|---|---|
| Which unit of modularity does AOP introduce, compared with classes in OOP? | spring-boot/spring-boot-aop-concepts.md | 1 | 1 | 1 |
| How does Spring AOP relate to the Spring IoC container? | spring-boot/spring-boot-aop-concepts.md | 1 | 1 | 1 |
| What is different about Spring declarative transaction management compared with EJB CMT? | spring-boot/spring-boot-tx-declarative.md | 2 | 1 | 1 |
| What does the value of the @Pointcut annotation contain? | spring-boot/spring-boot-aop-pointcuts.md | 3 | 1 | 1 |
| How do you declare before advice in an aspect? | spring-boot/spring-boot-aop-advice.md | 1 | 1 | 1 |
| Which two mechanisms can Spring AOP use to create proxies? | spring-boot/spring-boot-aop-proxying.md | 2 | 1 | 1 |
| Are checked exceptions rolled back by default in declarative transactions? | spring-boot/spring-boot-tx-rollback.md | 1 | 3 | 1 |
| What is the difference between PROPAGATION_REQUIRED and PROPAGATION_REQUIRES_NEW? | spring-boot/spring-boot-tx-propagation.md | ✗ | 1 | 2 |
| What are the two RAG stages, and is indexing online or offline? | langchain4j/langchain4j-rag-core-concepts.md | 1 | 1 | 1 |
| Which document splitters does LangChain4j provide out of the box? | langchain4j/langchain4j-rag-splitters-and-segments.md | 1 | 1 | 1 |
| What is Metadata in a Document useful for? | langchain4j/langchain4j-rag-document-pipeline.md | 1 | 1 | 1 |
| What is the EmbeddingStoreIngestor responsible for? | langchain4j/langchain4j-rag-embedding-and-store.md | 2 | 1 | 1 |
| Which component is the entry point of an advanced RAG pipeline? | langchain4j/langchain4j-rag-naive-and-advanced.md | 1 | 1 | 1 |
| Is there a built-in content retriever for web search instead of a vector store? | langchain4j/langchain4j-rag-content-retriever.md | ✗ | 2 | 2 |
| 《联合国宪章》第 53 条里提到的“敌国”指的是谁？ | enemy-state-clause/un-charter-chapter-8.md | ✗ | ✗ | ✗ |
| 国际托管制度适用于哪些领土？和敌国有什么关系？ | enemy-state-clause/un-charter-chapter-12.md | 1 | 1 | 1 |
| 《联合国宪章》第 107 条到底写了什么？ | enemy-state-clause/un-charter-chapter-17.md | ✗ | 4 | ✗ |
| 所谓“敌国条款”到底指哪几条，规定了什么内容？ | enemy-state-clause/gmw-enemy-clause-explained.md | ✗ | 1 | 2 |
| 日本说“敌国条款”已经过时，中方是怎么回应的？ | enemy-state-clause/mfa-enemy-clause-still-valid.md | 1 | 1 | 1 |
| 我国大熊猫野外种群现在有多少只？降级消息是在哪个部门的发布会上公布的？ | wildlife/chinanews-panda-downgraded-to-vulnerable.md | 2 | 1 | 1 |
| 世界自然保护联盟的濒危物种红色名录把受威胁等级分成哪几档？ | wildlife/chinanews-panda-downgraded-to-vulnerable.md | 1 | 1 | 1 |
| 全球唯一圈养的棕色大熊猫叫什么名字？它是怎么被发现的？ | wildlife/xinhua-brown-panda-qizai.md | 1 | 1 | 1 |
| 大熊猫国家公园整合了多少个自然保护地？ | wildlife/forestry-giant-panda-reserve.md | 1 | 1 | 1 |
| 2022 年泄露的《GTA6》片段有多少个，最后是在哪里被放出来的？ | gta6-leak/gamersky-breach-trial-details.md | 2 | 2 | 1 |
| 泄露《GTA6》的黑客在 R 星内部系统里给员工发了什么消息？ | gta6-leak/gamersky-hacker-found-guilty.md | 1 | 1 | 1 |
| 泄露《GTA6》的黑客最后被判了什么？他为什么没有进监狱？ | gta6-leak/163-hacker-hospital-order.md | ✗ | 5 | ✗ |
| In Spring AOP support, what takes the role that a class plays in object-oriented design? | spring-boot/spring-boot-aop-concepts.md | 1 | 1 | 1 |
| A method throws an exception that the compiler forces me to handle — will Spring roll my transaction back by default? | spring-boot/spring-boot-tx-rollback.md | ✗ | 3 | ✗ |
| I want to cut long text into pieces LangChain4j can embed — what ready-made options does it offer? | langchain4j/langchain4j-rag-splitters-and-segments.md | ✗ | ✗ | ✗ |
| I am building something fancier than naive RAG — which LangChain4j component do I start from? | langchain4j/langchain4j-rag-naive-and-advanced.md | ✗ | ✗ | ✗ |
| 宪章里说的那种“战败国”，具体是指哪些国家？ | enemy-state-clause/un-charter-chapter-8.md | ✗ | 4 | 5 |
| “敌国条款”这个说法，到底对应宪章里的哪几条文字？ | enemy-state-clause/gmw-enemy-clause-explained.md | 4 | 1 | 1 |
| 中国的野生大熊猫现在大概还剩多少只？ | wildlife/chinanews-panda-downgraded-to-vulnerable.md | 5 | 4 | 3 |
| 为了保护大熊猫，国家把多少个保护区合并到一起了？ | wildlife/forestry-giant-panda-reserve.md | 2 | 3 | 2 |
| 2022 年那波 GTA6 的实机画面一共漏出来多少段？在哪儿被集中放出的？ | gta6-leak/gamersky-breach-trial-details.md | ✗ | 2 | ✗ |
| 那个泄露 GTA6 的年轻人最后落得什么下场？ | gta6-leak/163-hacker-hospital-order.md | ✗ | ✗ | ✗ |

## 结论（local-hashing-v1 口径）

- 文档级 Hit@5：关键词 BM25 0.944、向量 0.806、混合（w=0.65）0.861；当前最强单路是 关键词 BM25，混合落后 0.083（文档级 Hit@5）
- 文档级 Hit@1：混合 0.667、关键词 0.667、向量 0.556；融合换来的是「头部排序更稳」，代价是尾部召回被向量通道稀释。
- **融合策略验收（D-20 标准：文档级 Hit@1 与 MRR 同时超过纯 BM25）**：**未通过** —— RRF 0.611 / 0.722 vs BM25 0.667 / 0.774（片段级：RRF 0.653 vs BM25 0.707）
  → 名次融合赢召回、输排头：按 D-21 的结论，不换默认融合，改走「RRF 召回 + 精排」路线。
- 精排对照本轮未跑（`-Drag.eval.rerank=dashscope` 需要 DashScope key）。
- 权重扫描：文档级 Hit@5 在 w ∈ [0.20, 0.80] 上最高出现在 w=0.20（0.889），最好的一档 MRR=0.753 —— 离线哈希向量与 BM25 的候选高度重合，w 只影响 MRR 与 Hit@1，而这套口径下它无从体现。
- 口径提醒：`local-hashing-v1` 与 BM25 吃的是同一批 token，向量通道几乎只额外带来哈希噪声，所以这套离线口径天然偏向关键词通道；
  要验证生产融合权重（0.65 : 0.35）需要换成真实 embedding 模型重跑：
  `mvn -B test -Dtest=RagEvaluationTest -Drag.eval.model=dashscope -Drag.eval.report=target/bench/rag-eval-dashscope.md`（需 `RAG_EMBEDDING_API_KEY`）。
