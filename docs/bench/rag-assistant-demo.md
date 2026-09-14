# 端到端问答演示（AiServices + ContentRetriever）

由 `Langchain4jRagAssistantTest` 生成。对话模型 `qwen-plus`、向量模型 `text-embedding-v4`，语料为 `rag-eval` 的 33 篇公开文档。

两条链路对同一个问题各答一遍：**框架原生**（Ingestor → InMemoryEmbeddingStore → EmbeddingStoreContentRetriever）与**自研检索接入框架**（混合召回 + 精排 → 适配成 ContentRetriever）。

## 问题：In Spring's declarative transactions, are checked exceptions rolled back by default?

期望出处：`spring-boot/spring-boot-tx-rollback.md`（答案字面串：Checked exceptions that are thrown from a transactional method）

自研检索（生产同款参数 top-4）：文档命中 是、答案片段命中 否

### 检索命中（自研：混合 + 精排，top-4）

- [1] `spring-boot/spring-boot-tx-rollback.md` > Rolling Back a Declarative Transaction ｜ andled Exception as it bubbles up
- [2] `spring-boot/spring-boot-tx-rollback.md` > Rolling Back a Declarative Transaction ｜ details on controlling rollback semantics declaratively
- [3] `spring-boot/spring-boot-tx-declarative.md` > Declarative Transaction Management ｜ Although EJB container default behavior automatically rolls back the transaction on a
- [4] `spring-boot/spring-boot-tx-rollback.md` > Rolling Back a Declarative Transaction ｜ can be checked using the isFailure() method on the Try instance.

### 检索命中（框架原生：向量单路，top-4）

- [1] `spring-boot/spring-boot-tx-rollback.md` ｜ The recommended way to indicate to the Spring Framework’s transaction infrastructure
- [2] `spring-boot/spring-boot-tx-declarative.md` ｜ For example, they typically do not need to import Spring
- [3] `spring-boot/spring-boot-tx-rollback.md` ｜ ```java
- [4] `spring-boot/spring-boot-tx-declarative.md` ｜ You can specify this declaratively, in

### 回答（自研检索 → AiServices）

No, checked exceptions are not rolled back by default in Spring’s declarative transactions.

- By default, Spring marks a transaction for rollback **only for unchecked exceptions** (i.e., instances or subclasses of `RuntimeException`) and `Error`.
- **Checked exceptions** (e.g., `Exception` subclasses other than `RuntimeException`, such as `IOException` or `SQLException`) **do not trigger automatic rollback**.
- This behavior aligns with EJB convention, where application exceptions (checked exceptions) do not cause automatic rollback, unlike system exceptions (runtime exceptions).
- Custom rollback rules can be configured declaratively using the `@Transactional` annotation — for example, via the `rollbackFor` attribute to specify checked exceptions that should trigger rollback.

来源（由检索结果程序化附加，不交给模型生成）：spring-boot/spring-boot-tx-rollback.md、spring-boot/spring-boot-tx-declarative.md

### 回答（框架原生检索 → AiServices）

No, checked exceptions are not rolled back by default in Spring’s declarative transactions.

- By default, Spring rolls back transactions only for **unchecked exceptions** (i.e., subclasses of `RuntimeException`) and `Error`.
- **Checked exceptions** (e.g., `Exception` or its subclasses like `IOException`, `SQLException`) **do not trigger rollback** unless explicitly configured.
- This behavior aligns with EJB CMT convention but can be customized using **rollback rules**, either via the `@Transactional(rollbackFor = ...)` attribute or XML configuration.
- The example code shows handling a `DataAccessException` (a runtime exception) in a `try-catch`, but the key point is that catching and rewrapping a checked exception in a `CompletableFuture.failedFuture()` — without throwing it — also avoids triggering rollback, since no exception propagates unhandled.

来源（由检索结果程序化附加）：spring-boot/spring-boot-tx-rollback.md、spring-boot/spring-boot-tx-declarative.md

## 问题：所谓“敌国条款”到底指哪几条，规定了什么内容？

期望出处：`enemy-state-clause/gmw-enemy-clause-explained.md`（答案字面串：第53条、第77条及第107条）

自研检索（生产同款参数 top-4）：文档命中 是、答案片段命中 是

### 检索命中（自研：混合 + 精排，top-4）

- [1] `enemy-state-clause/gmw-enemy-clause-explained.md` ｜ > 原文出处：光明网《蓝厅见》栏目 2026-09-03《日本投降 81 周年，中方为何再提“敌国条款”？》（正文为中新社视频稿摘录；抓取时未保留栏目直链，故 front-matt…
- [2] `enemy-state-clause/mfa-enemy-clause-still-valid.md` ｜ 9月8日，外交部发言人毛宁主持例行记者会。
- [3] `enemy-state-clause/sina-why-emphasize-enemy-clause.md` ｜ 近期，日本政府在逃避二战历史罪责、挑战战后国际秩序上动作频频，高调宣称《联合国宪章》中的“敌国条款”已经过时、形同虚设，彻底暴露出日本右翼势力企图摆脱战后体制约束，加速突破和平宪法…
- [4] `enemy-state-clause/sina-why-emphasize-enemy-clause.md` ｜ 国条款”仍完整保留在《联合国宪章》文本中，具有完全的法律效力。日本政府单方面声称“‘敌国条款’已经过时”，无异于公然背叛对国际社会的承诺、公开否定其加入联合国的法理基础。

### 检索命中（框架原生：向量单路，top-4）

- [1] `enemy-state-clause/gmw-enemy-clause-explained.md` ｜ 倘若日本政府真心想要摆脱“敌国条款”带来的约束，出路不在于想方设法删除条文，而在于彻底清除军国主义幽灵。当战争扩张的隐患不复存在，条款自然也就失去了存在的现实必要。(记者 刘世炯）
- [2] `enemy-state-clause/gmw-enemy-clause-explained.md` ｜ > 原文出处：光明网《蓝厅见》栏目 2026-09-03《日本投降 81 周年，中方为何再提“敌国条款”？》（正文为中新社视频稿摘录；抓取时未保留栏目直链，故 front-matt…
- [3] `enemy-state-clause/sina-why-emphasize-enemy-clause.md` ｜ 近期，日本政府在逃避二战历史罪责、挑战战后国际秩序上动作频频，高调宣称《联合国宪章》中的“敌国条款”已经过时、形同虚设，彻底暴露出日本右翼势力企图摆脱战后体制约束，加速突破和平宪法…
- [4] `enemy-state-clause/mfa-enemy-clause-still-valid.md` ｜ 9月8日，外交部发言人毛宁主持例行记者会。

### 回答（自研检索 → AiServices）

“敌国条款”指《联合国宪章》第53条、第77条和第107条，其核心内容是：德、意、日等二战法西斯或军国主义战败国，若再次实施侵略政策的任何步骤，中、法、苏（俄）、英、美等联合国创始会员国有权直接对其采取军事行动，无须经联合国安理会授权。

要点如下：  
- 条款性质：是针对二战轴心国的特殊制度安排，属战后国际秩序的法律保障机制；  
- 适用对象：明确指向德国、意大利、日本等法西斯/军国主义国家及其仆从国；  
- 法律效力：至今仍完整保留在《联合国宪章》文本中，具有完全法律效力；  
- 行动权限：创始会员国可绕过安理会自主采取和平或强制措施，包括军事行动；  
- 设立目的：防止战败国复活侵略政策、再度发动战争，是对历史教训的制度性回应。

来源（由检索结果程序化附加，不交给模型生成）：enemy-state-clause/gmw-enemy-clause-explained.md、enemy-state-clause/mfa-enemy-clause-still-valid.md、enemy-state-clause/sina-why-emphasize-enemy-clause.md

### 回答（框架原生检索 → AiServices）

“敌国条款”指《联合国宪章》第53条、第77条和第107条中针对二战轴心国（德、意、日等法西斯或军国主义国家）的特殊规定。

要点如下：  
1. **适用对象明确**：特指二战中的法西斯轴心国及其仆从国，尤其是德国、意大利、日本；  
2. **核心授权内容**：若上述“敌国”任何一国再次采取侵略政策的任何步骤，中国、法国、苏联（后由俄罗斯继承）、英国、美国等联合国创始会员国可**不经安理会授权，直接对其实施军事行动**；  
3. **法律性质**：是战后国际秩序的制度性保障，属于《联合国宪章》中仍具完全法律效力的现行条款，并非历史废文；  
4. **设立目的**：基于反法西斯战争惨痛教训，旨在防止战败国复活军国主义、重走侵略扩张老路，维护亚太及全球和平安全；  
5. **现实约束力**：日本因未彻底反省和切割军国主义，该条款持续构成对其军事冒进行为的法律警示与制约。

来源（由检索结果程序化附加）：enemy-state-clause/gmw-enemy-clause-explained.md、enemy-state-clause/sina-why-emphasize-enemy-clause.md、enemy-state-clause/mfa-enemy-clause-still-valid.md

## 问题：2022 年泄露的《GTA6》片段有多少个，最后是在哪里被放出来的？

期望出处：`gta6-leak/gamersky-breach-trial-details.md`（答案字面串：90个《GTA6》开发片段在GTAForums上泄露）

自研检索（生产同款参数 top-4）：文档命中 是、答案片段命中 是

### 检索命中（自研：混合 + 精排，top-4）

- [1] `gta6-leak/gamersky-90-clips-review.md` ｜ 2022年，多达90个《GTA6》开发片段被泄露，震惊了整个游戏界。如今，《GTA6》已经正式公布，并放出了第二支预告片，一些玩家在欣喜之余，开始回顾起了这些泄露片段。
- [2] `gta6-leak/gamersky-breach-trial-details.md` ｜ 2022年9月，《GTA6》遭遇黑客泄露事件，90多段有关视频被放出。根据近日报道，英国法庭已经裁定18岁的黑客Arion Kurtaj作为Lapsus$黑客组织的成员参与了这次攻…
- [3] `gta6-leak/gamersky-90-clips-leaked.md` ｜ 就在刚刚，有疑似《GTA6》PC版的早期泄露片段流传到了网络上的消息。
- [4] `gta6-leak/3dm-breach-2nd-anniversary.md` ｜ 《GTA 5》最近迎来了 11 周年，而《GTA 6》首支预告片也即将迎来一周年。但

### 检索命中（框架原生：向量单路，top-4）

- [1] `gta6-leak/gamersky-90-clips-leaked.md` ｜ 就在刚刚，有疑似《GTA6》PC版的早期泄露片段流传到了网络上的消息。
- [2] `gta6-leak/gamersky-90-clips-review.md` ｜ 2022年，多达90个《GTA6》开发片段被泄露，震惊了整个游戏界。如今，《GTA6》已经正式公布，并放出了第二支预告片，一些玩家在欣喜之余，开始回顾起了这些泄露片段。
- [3] `gta6-leak/3dm-breach-2nd-anniversary.md` ｜ 《GTA 5》最近迎来了 11 周年，而《GTA 6》首支预告片也即将迎来一周年。但
- [4] `gta6-leak/gamersky-rockstar-anti-leak.md` ｜ 据GameSpot报道，Rockstar Games近日在英国格拉斯哥就业仲裁法庭的听证会上披露了更多保护《GTA6》游戏机密的措施。为了防止游戏内容遭到偷拍和泄露，Rocksta…

### 回答（自研检索 → AiServices）

2022年泄露的《GTA6》片段共有90个（或称“多达90个”“90多段”），最后是在GTAForums上被放出的。

要点：  
- 泄露发生于2022年9月18日；  
- 黑客Arion Kurtaj通过网络钓鱼攻击获取R星内部数据，下载并威胁发布《GTA6》内容；  
- 他最终在GTAForums论坛发布了这90个开发片段；  
- 泄露后不久，黑客即被当场抓获；  
- 这些片段包含男女主角外观、趴伏机制、AI寻路、车辆损坏、布料物理等早期开发内容。

来源（由检索结果程序化附加，不交给模型生成）：gta6-leak/gamersky-90-clips-review.md、gta6-leak/gamersky-breach-trial-details.md、gta6-leak/gamersky-90-clips-leaked.md、gta6-leak/3dm-breach-2nd-anniversary.md

### 回答（框架原生检索 → AiServices）

2022年泄露的《GTA6》片段共有90个（资料中多次明确提到“多达90个”“90多个”“近100个”，以最常出现的精确数字“90个”为准），最后是被黑客Arion Kurtaj通过入侵R星印度团队内部聊天频道后，将这些视频片段泄露到网上。

要点：  
- 泄露发生于2022年9月18日；  
- 黑客使用网络钓鱼邮件攻击R星印度团队一名员工，获取其浏览器数据并进入公司内部聊天频道；  
- 泄露内容包括约90个开发视频片段，涵盖主角外观、趴伏机制、AI测试、物理效果等；  
- 泄露平台为互联网公开渠道（资料未指明具体网站，仅称“流传到了网络上”“被泄漏到网上”，Reddit等社区后续有讨论和分析，但原始泄露并非首发于Reddit）；  
- Rockstar随后删除内容并起诉黑客。

来源（由检索结果程序化附加）：gta6-leak/gamersky-90-clips-leaked.md、gta6-leak/gamersky-90-clips-review.md、gta6-leak/3dm-breach-2nd-anniversary.md、gta6-leak/gamersky-rockstar-anti-leak.md

## 小结

- 三条问题的期望文档全部被自研检索召回（文档级 3/3）；
- 生产同款参数（top-4）下，答案片段进前 4 的有 2/3 题 —— 这正是 D-22 记的片段级短板在生产设置下的现场表现；
- 两条链路的回答都由 AiServices 生成（同一个 assistant 接口、同一个 ChatModel，只换 ContentRetriever）；出处清单由代码从检索结果拼出 —— 模型可以编路径，检索结果里的 sourcePath 是真值，这一条也适用于生产。
