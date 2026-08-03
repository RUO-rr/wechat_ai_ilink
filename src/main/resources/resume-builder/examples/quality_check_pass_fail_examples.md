<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Output Quality Checklist — Pass/Fail Cases

> 示例目的：把抽象质量检查项转换成可检查的 pass/fail 标准。  
> 本案例为虚构示范。

## 1. Factual Integrity

### Fail

```markdown
- 以第一作者身份发表 JACS 论文一篇。
```

User evidence only says:

```markdown
参与课题，论文准备投稿。
```

Why it fails:

- Fabricates journal.
- Fabricates authorship.
- Fabricates publication status.

### Pass

```markdown
- 参与甲硫氨酸化学标记课题的数据整理与图表分析，相关论文处于准备阶段，题目和作者顺序待确认。
```

Why it passes:

- Does not overstate status.
- Marks uncertainty.
- Preserves evidence boundary.

## 2. Bullet Structure

### Fail

```markdown
- 熟悉蛋白质组学、Python 和数据分析，学习能力强。
```

Why it fails:

- No task.
- No method.
- No result.
- No evidence.

### Pass

```markdown
- 使用 Python 对 pFind 输出的修饰肽段表进行清洗、去重和分组统计，生成蛋白数量、位点数量和样本重叠结果，用于比较不同处理组的标记差异。
```

Why it passes:

- Action: 使用 Python
- Task: 清洗、去重、分组统计
- Method/tool: pFind 输出表、Python
- Result: 数量统计和样本重叠结果
- Relevance: 比较标记差异

## 3. Keyword Use

### Fail

```markdown
技能：机器学习、深度学习、蛋白质组学、药物研发、GMP、项目管理、商业分析。
```

Why it fails:

- Keyword stuffing.
- No evidence.
- May damage credibility.

### Pass

```markdown
技能：pFind 数据整理、修饰位点统计、Python 表格处理、Venn 图与频率热图绘制、LC-MS/MS 结果解读。
```

Why it passes:

- Each skill can map to project evidence.
- Specific enough for review.
- No unsupported buzzwords.

## 4. Missing Information

### Fail

```markdown
请补充所有个人信息、所有项目、所有技能、所有证书、所有获奖、所有论文、所有实习。
```

Why it fails:

- Asks for everything at once.
- Not decision-focused.

### Pass

```markdown
当前最影响简历决策的信息有 4 项：
1. 目标岗位或 JD；
2. 蛋白质组学项目的个人贡献边界；
3. 是否实际完成过样品制备、酶切或除盐；
4. Python/R 脚本或图表是否可作为作品证据。
```

Why it passes:

- Focuses on decision-critical gaps.
- Prioritizes what changes resume selection.

## 5. ATS Compatibility

### Fail

```markdown
核心经历全部放在图片、文本框或复杂表格中。
```

Why it fails:

- ATS may not parse content.
- Text may not be selectable.
- Section headings may be lost.

### Pass

```markdown
Education
Research Experience
Projects
Skills
Publications
```

Why it passes:

- Standard headings.
- Plain text structure.
- Parser-friendly.

## 6. Layout Density

### Fail

```markdown
一个项目下有 9 条 bullet，每条超过 120 个中文字，PDF 中字体小于 8.5 pt。
```

Why it fails:

- Excessive density.
- Low readability.
- Likely clipping or poor scanning.

### Pass

```markdown
一个核心项目保留 3–4 条 bullet，每条约 35–80 个中文字；非核心经历压缩为 1–2 条。
```

Why it passes:

- Prioritizes lead evidence.
- Maintains readability.
- Easier to fit one page.

## 7. Terminology Consistency

### Fail

Same resume uses:

```markdown
蛋白组学 / 蛋白质组学 / proteomics analysis / protein omics
```

without reason.

Why it fails:

- Inconsistent terminology.
- Looks unpolished.
- May confuse ATS and reviewer.

### Pass

```markdown
蛋白质组学（proteomics）
```

First use introduces bilingual term; later use stays consistent.

## 8. PDF Claim

### Fail

```markdown
PDF 已生成并检查完成。
```

when rendering failed.

Why it fails:

- False delivery claim.

### Pass

```markdown
PDF 渲染失败，暂提供 HTML 和 Markdown。当前未声称 PDF 已生成；建议修复渲染环境后再输出 PDF。
```

Why it passes:

- Honest about failure.
- Provides fallback.
