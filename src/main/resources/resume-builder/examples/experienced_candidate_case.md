<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Experienced Candidate Case

> 示例目的：展示有工作经验候选人如何重排内容，避免学生项目过度前置。  
> 本案例为虚构示范。

## 1. Scenario

Candidate:

- 5 years of experience in analytical chemistry / QC.
- Applying for an analytical scientist role in a pharmaceutical company.
- Has older graduate research experience but current work is more relevant.

## 2. Internal Classification

```json
{
  "candidate_stage": "EXPERIENCED",
  "candidate_modifiers": ["HAS_WORK_EXPERIENCE", "RESEARCH_ORIENTED"],
  "resume_scenario": "CORPORATE_JOB_RESUME",
  "language_market": "CHINESE",
  "workflow_mode": "TARGETED_REWRITE",
  "confidence": "HIGH",
  "missing_decision_fields": ["target_jd_url", "verified_metrics"]
}
```

## 3. Common Problem

Experienced candidates often keep old academic projects at the top because those projects sound technical. For corporate roles, recent job scope and verified outcomes usually carry more weight.

## 4. Before

```markdown
科研项目  
硕士期间参与天然产物分离项目，熟悉柱层析、NMR 和 HPLC。

工作经历  
某检测公司 分析员  
负责日常检测工作，完成领导安排的任务。
```

## 5. Diagnosis

| Issue | Why it matters | Fix |
|---|---|---|
| Recent work is underwritten | The target role values current platform experience | Expand work experience |
| Old research is over-prioritized | Academic project may be less relevant than current QC work | Move after work experience |
| No scope or throughput | Cannot assess scale | Add sample types, methods, instruments, batches if verified |
| No compliance context | Pharma roles value SOP, GMP, data integrity | Add if evidence exists |
| Generic wording | “完成领导安排” is low-value | Rewrite with task/method/output |

## 6. After

```markdown
工作经历｜分析员，某检测公司｜2021.07–至今

- 按照 SOP 执行 HPLC/UPLC 方法下的样品检测、系统适用性确认和结果复核，支持原料、中间体或成品的常规质量分析。
- 整理批次检测记录、谱图和异常样品复查信息，配合完成数据归档和内部审计准备，降低结果追溯中的信息缺口。
- 参与方法转移或方法适用性确认相关实验，记录关键色谱条件、样品处理步骤和偏差情况，为后续方法稳定运行提供依据。

科研经历｜天然产物分离与结构表征项目｜硕士阶段

- 使用柱层析、HPLC 和 NMR 对目标组分进行分离纯化与结构确认，形成实验记录和阶段性汇报。
```

## 7. Selection Decision

| Experience | Decision | Reason |
|---|---|---|
| Current analytical chemistry role | lead | Recent, directly relevant, work-centered evidence |
| Method transfer / SOP / audit evidence | lead if verified | Strong corporate relevance |
| Graduate natural product project | support | Technical relevance but older |
| Generic coursework | omit | Low relevance |
| Student club experience | omit | Not useful for experienced analytical role |

## 8. Quality Notes

For experienced candidates:

- Do not lead with education unless the target explicitly requires school prestige or degree.
- Avoid long lists of old student projects.
- Prioritize recent work, scope, tools, stakeholders, compliance, deliverables, and impact.
- Quantify only verified information.
- If metrics are missing, use scope and output instead of inventing numbers.
