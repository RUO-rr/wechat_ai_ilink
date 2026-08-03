<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Career Changer Case

> 示例目的：展示转行候选人如何前置可迁移能力和新领域证据，同时避免夸大直接经验。  
> 本案例为虚构示范。

## 1. Scenario

Candidate:

- Background: wet-lab biology research assistant.
- Target: bioinformatics analyst intern.
- Has limited Python experience from personal projects and data analysis coursework.
- No full-time bioinformatics role yet.

## 2. Internal Classification

```json
{
  "candidate_stage": "CAREER_CHANGER",
  "candidate_modifiers": ["CROSS_DISCIPLINARY", "HAS_TRANSFERABLE_SKILLS", "HAS_NEW_FIELD_TRAINING", "RESEARCH_ORIENTED"],
  "resume_scenario": "INTERNSHIP_RESUME",
  "language_market": "ENGLISH",
  "workflow_mode": "TARGETED_APPLICATION",
  "confidence": "MEDIUM",
  "missing_decision_fields": ["portfolio_link", "target_jd", "course_project_details"]
}
```

## 3. Risk

The candidate should not claim:

```markdown
Experienced bioinformatics analyst with strong NGS pipeline development experience.
```

if they only have coursework and small projects.

## 4. Better Positioning

```markdown
Biology-trained candidate transitioning into bioinformatics, with hands-on wet-lab research experience and project-based Python data analysis training. Interested in applying reproducible data-processing workflows to biological datasets.
```

## 5. Evidence Classification

| Evidence | Category | Use |
|---|---|---|
| Wet-lab sample preparation and experimental design | Transferable domain knowledge | support |
| Python course project analyzing gene expression table | New-field evidence | lead |
| R basics from statistics class | New-field evidence | support |
| No NGS production pipeline experience | Gap | list as missing/risk |
| General communication skills | Low-value unless evidenced | omit or fold into project |

## 6. Resume Bullet Rewrite

### Bad

```markdown
- 熟悉生物信息学分析，能够进行转录组数据处理。
```

Why it fails:

- Overstates capability.
- No dataset, tool, method, or output.
- Could mislead the employer.

### Good

```markdown
- Built a Python-based data-cleaning notebook for a course gene-expression dataset, applying table normalization, missing-value checks, group comparison, and matplotlib visualization to produce a reproducible analysis summary.
```

### Good Chinese Version

```markdown
- 基于课程提供的基因表达数据表，使用 Python notebook 完成数据清洗、缺失值检查、分组比较和 matplotlib 可视化，输出可复现的数据分析摘要。
```

## 7. Experience Ordering

Recommended order:

1. Profile summary emphasizing transition.
2. Bioinformatics/data projects.
3. Technical skills with evidence.
4. Wet-lab research experience.
5. Education.
6. Additional activities, only if relevant.

## 8. Gap Statement

For match report, not necessarily resume:

```markdown
目前缺少真实生产环境下的 NGS pipeline、Linux/HPC 和 Snakemake/Nextflow 证据。如果目标岗位强制要求这些内容，应在 missing-info 或 learning plan 中列出，不应直接写进简历技能栏。
```

## 9. Quality Notes

For career changers:

- Use transferable evidence but label it correctly.
- Place new-field proof before old-field prestige.
- Avoid claiming direct experience when evidence is only adjacent.
- Highlight projects, portfolios, reproducible notebooks, and domain knowledge.
