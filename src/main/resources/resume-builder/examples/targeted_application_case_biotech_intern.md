<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Targeted Application Case — Biotech Intern

> 示例目的：展示从岗位来源到定制简历 bullet 的完整证据链。  
> 本案例为虚构示范，不代表真实公司或真实岗位。

## 1. User Request

> 我想申请一个生物技术公司的蛋白质组学实习岗位，下面是 JD 和我的经历，请帮我定制简历。

## 2. Job Source Summary

| Source ID | Source Type | Content | Access Date | Confidence |
|---|---|---|---|---|
| JS-001 | User-provided JD text | Proteomics Intern, Biotech Company X | 2026-06-12 | E: unverified user-provided source |
| JS-002 | Company research page, simulated | Protein biomarker discovery and LC-MS platform | 2026-06-12 | B: official-like simulated source |

> 示例中不使用真实 URL。真实执行时必须记录 URL、访问日期、来源类型和可信度。

## 3. Extracted Requirements

| Requirement ID | Requirement | Type | Weight | Source |
|---|---|---|---:|---|
| RQ-001 | Assist LC-MS/MS-based proteomics sample preparation and data analysis | Explicit | 5 | JS-001 |
| RQ-002 | Familiar with protein digestion, peptide cleanup, or sample prep workflows | Explicit | 4 | JS-001 |
| RQ-003 | Experience with Python/R for data processing or visualization | Explicit | 4 | JS-001 |
| RQ-004 | Understand protein biomarkers, PTMs, or chemical biology is preferred | Preferred | 3 | JS-002 |
| RQ-005 | Communicate results in clear reports and figures | Explicit | 3 | JS-001 |
| RQ-006 | Cell culture or molecular biology experience | Preferred | 2 | JS-001 |

## 4. Candidate Evidence Units

| Evidence ID | Candidate Evidence | Source | Credibility |
|---|---|---|---:|
| EV-001 | Completed undergraduate research project on methionine-related chemical proteomics | User resume | 0.8 |
| EV-002 | Used pFind outputs to summarize modified peptide, site, and protein counts across sample groups | User project note | 1.0 |
| EV-003 | Generated Venn diagrams and motif frequency heatmaps for modified sites | User project note | 1.0 |
| EV-004 | Performed trypsin digestion and desalting in a course research project | User statement, needs confirmation | 0.6 |
| EV-005 | Used Python scripts for tabular data processing and visualization | User portfolio | 0.8 |
| EV-006 | Cell culture experience from course lab only | User statement | 0.6 |

## 5. Matching Matrix

| Requirement | Weight | Evidence | Match Score | Credibility | Composite | Decision |
|---|---:|---|---:|---:|---:|---|
| RQ-001 | 5 | EV-001, EV-002 | 4 | 0.9 | 18.0 | lead |
| RQ-002 | 4 | EV-004 | 3 | 0.6 | 7.2 | support / needs confirmation |
| RQ-003 | 4 | EV-003, EV-005 | 4 | 0.9 | 14.4 | lead |
| RQ-004 | 3 | EV-001 | 4 | 0.8 | 9.6 | support |
| RQ-005 | 3 | EV-003 | 4 | 1.0 | 12.0 | lead |
| RQ-006 | 2 | EV-006 | 2 | 0.6 | 2.4 | omit or brief skills mention |

## 6. Experience Selection Decision

| Candidate Experience | Decision | Reason |
|---|---|---|
| Methionine chemical proteomics data analysis project | lead | Covers LC-MS/MS data, modified sites, Python/data visualization, chemical biology |
| Sample preparation course project | support | Relevant but low confidence; mark as `需用户确认` if used |
| General cell culture course | omit | Low weight and weak evidence |
| Student union activity | omit | Low relevance to proteomics intern role |
| Generic self-evaluation | omit/rewrite | Replace with evidence-based profile summary |

## 7. Targeted Resume Bullets

### Bad Version

```markdown
- 参与蛋白质组学项目，熟悉质谱分析和 Python，具有良好的数据分析能力。
```

Why it fails:

- “熟悉”缺少证据；
- 没有任务、方法、结果；
- 无法对应具体岗位要求；
- 不可追溯。

### Good Version

```markdown
- 围绕甲硫氨酸相关化学蛋白质组学数据，整理 pFind 检索结果中的修饰肽段、位点和蛋白数量，比较不同处理组的标记分布，为探针标记特异性评估提供数据依据。
- 使用 Python 对修饰位点表进行清洗、去重和分组统计，生成 Venn 图与位点邻近氨基酸频率热图，支持不同样本组之间的蛋白质组学差异比较。
- 基于 LC-MS/MS 鉴定结果提取修饰位点周围序列，进行 motif 频率统计并区分样本背景与全蛋白组背景，用于分析潜在序列偏好。
```

## 8. Traceability Table

| Bullet ID | Final Bullet Summary | Evidence IDs | Requirement IDs | Source IDs |
|---|---|---|---|---|
| BL-001 | pFind modified peptide/site/protein count analysis | EV-001, EV-002 | RQ-001, RQ-004 | JS-001, JS-002 |
| BL-002 | Python cleaning, Venn diagram, heatmap | EV-003, EV-005 | RQ-003, RQ-005 | JS-001 |
| BL-003 | Motif frequency and background comparison | EV-003 | RQ-001, RQ-004, RQ-005 | JS-001, JS-002 |

## 9. Missing Information

### Critical

- 是否实际完成过蛋白酶切、除盐、样品制备；
- 是否有具体 LC-MS/MS 仪器或平台名称；
- Python/R 脚本是否可展示；
- 项目时间、课题组/导师、个人贡献边界。

### Optional

- 是否有 poster、论文、报告、代码仓库；
- 是否有英文简历版本；
- 是否需要 ATS 友好版本。

## 10. Default Deliverables

For this case, default outputs should be:

1. `job-analysis-report.md`
2. `matching-matrix.md`
3. `targeted-resume.md` or `targeted-resume.html`
4. `missing-info.md`

Do not generate PDF, bilingual version, photo prompt, content tuning sheet, or full master profile JSON unless explicitly requested.
