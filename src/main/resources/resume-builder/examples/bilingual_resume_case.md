<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Bilingual Resume Case

> 示例目的：展示中英文简历不应逐句直译，而应根据市场习惯独立打磨。  
> 本案例为虚构示范。

## 1. Scenario

Candidate:

- Master's student in pharmaceutical analysis.
- Applying to Chinese pharma companies and international R&D internship roles.
- Needs both Chinese and English resumes.

## 2. Internal Classification

```json
{
  "candidate_stage": "STUDENT",
  "candidate_modifiers": ["MASTER", "RESEARCH_ORIENTED", "INTERNATIONAL_EDUCATION"],
  "resume_scenario": "CORPORATE_JOB_RESUME",
  "language_market": "BILINGUAL",
  "workflow_mode": "FULL_DELIVERABLE_PACKAGE",
  "confidence": "HIGH",
  "missing_decision_fields": ["target_market_priority", "official_degree_translation"]
}
```

## 3. Chinese Version

```markdown
项目经历｜药物分析方法开发与验证

- 围绕小分子候选化合物的含量测定需求，建立 HPLC 分析方法，优化流动相比例、检测波长和样品前处理条件，提升目标峰分离度与结果稳定性。
- 按照方法学验证思路整理线性、精密度、重复性和稳定性数据，形成实验记录和阶段性汇报，用于支持后续样品检测流程。
```

## 4. Bad English Literal Translation

```markdown
Project Experience | Drug analysis method development and verification

- Around the content determination demand of small molecule candidate compound, established HPLC analysis method, optimized mobile phase ratio, detection wavelength and sample pretreatment condition, improved target peak separation degree and result stability.
```

### Why it fails

- Literal Chinese-English structure.
- Awkward terminology: “around the demand”, “verification”.
- Not ATS-friendly.
- Too long and hard to parse.

## 5. Better English Version

```markdown
Research Project | HPLC Method Development for Small-Molecule Analysis

- Developed an HPLC-based assay for small-molecule quantification by optimizing mobile-phase composition, detection wavelength, and sample-preparation conditions to improve peak resolution and analytical consistency.
- Organized method-validation data, including linearity, precision, repeatability, and stability results, into experimental records and progress reports for downstream sample-testing workflows.
```

## 6. Terminology Map

| Chinese | English | Notes |
|---|---|---|
| 药物分析 | pharmaceutical analysis | Use field term, not “drug analysis” unless context requires |
| 方法学验证 | method validation | Not “methodology verification” |
| 含量测定 | quantification / assay | Choose based on context |
| 流动相 | mobile phase | Standard HPLC term |
| 分离度 | resolution | Use “peak resolution” in HPLC context |
| 重复性 | repeatability | Distinguish from reproducibility if needed |

## 7. Privacy Difference

| Item | Chinese Resume | English Resume |
|---|---|---|
| Photo | Only if supplied/requested and suitable for China-market resume | Omit by default |
| Gender | Only if supplied/requested | Omit |
| Date of birth | Only if supplied/requested | Omit |
| Political affiliation | Only if supplied/requested | Omit |
| Address | City or broader location preferred | City/country only if relevant |

## 8. Quality Notes

For bilingual resumes:

- Write two independent versions.
- Preserve facts, not sentence order.
- Use official translations for degree, university, company, journal, and patent names.
- Build a terminology map for technical terms.
- Apply each market's privacy convention separately.
