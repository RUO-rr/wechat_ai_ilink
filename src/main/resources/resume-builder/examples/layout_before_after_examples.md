<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Layout and File Quality Before/After

> 示例目的：展示简历排版、断行、密度、ATS 和 HTML/PDF 输出中的常见问题。  
> 本案例为虚构示范。

## 1. Chinese Token Breaking

### Bad

```text
蛋白质组
学数据分析
```

or

```text
物理
化学
```

### Why it fails

Short semantic tokens should not be broken across lines. Technical terms, major names, method names, school names, company names, and chemical/protein names should remain intact when possible.

### Better

```text
蛋白质组学数据分析
```

If space is insufficient, move the entire token group to the next line.

---

## 2. Manual `<br>` for Visual Alignment

### Bad

```html
<li>使用 Python 完成数据清洗、统计分析、<br>绘图和结果整理。</li>
```

### Why it fails

Manual line breaks make the layout fragile. They may break differently in PDF, browser, or print.

### Better

```html
<li>使用 Python 完成数据清洗、统计分析、绘图和结果整理。</li>
```

Use CSS for width, spacing, line height, and alignment.

---

## 3. Over-Dense Project Entry

### Bad

```markdown
蛋白质组学项目  
使用 pFind 处理多组数据，进行开放搜索、闭源搜索、位点统计、蛋白统计、肽段统计、差异比较、motif 分析、保守性分析、热力图绘制、韦恩图绘制、结果解释、报告撰写、代码整理、数据清洗、数据库比对、参数设置、图片美化。
```

### Why it fails

- One long paragraph.
- No prioritization.
- Too many methods without structure.
- Hard to scan.

### Better

```markdown
蛋白质组学数据分析项目

- 使用 pFind 输出结果整理修饰肽段、位点和蛋白数量，比较不同样本组的标记分布。
- 基于 Python 完成数据清洗、去重、分组统计和 Venn 图绘制，支持样本间重叠关系分析。
- 提取修饰位点邻近序列并绘制氨基酸频率热图，用于分析潜在 motif 偏好。
```

---

## 4. ATS-Unfriendly Visual Resume

### Bad

```text
两栏布局 + 图标联系方式 + 文本框项目经历 + 技能进度条 + 图片化标题
```

### Why it fails

- ATS may skip or reorder content.
- Icons and progress bars do not carry semantic meaning.
- Text boxes may parse incorrectly.

### Better ATS Version

```markdown
# Name

Email | Phone | City

## Education

## Research Experience

## Projects

## Skills

## Publications
```

For visual delivery, HTML/PDF can be more designed, but ATS version should stay simple.

---

## 5. Font Size and Density

### Recommended Gates

For one-page Chinese resume:

- Body font usually 9.5–11 pt equivalent.
- Each core project: 2–4 bullets.
- Each bullet: approximately 35–80 Chinese characters.
- Avoid more than 6 bullets under a single experience unless it is an academic CV.
- Avoid paragraphs longer than 3 rendered lines.

For one-page English resume:

- Body font usually 9–11 pt equivalent.
- Each bullet: approximately 12–28 words.
- Each project/experience: 2–5 bullets.
- Avoid dense narrative paragraphs.

For academic CV:

- May exceed one page.
- Do not compress publications, grants, presentations, and teaching into unreadable density.

---

## 6. Before/After Section Order

### Bad for Targeted Biotech Internship

```markdown
自我评价
校园经历
兴趣爱好
教育背景
项目经历
技能
```

### Better

```markdown
教育背景
科研/项目经历
技术技能
实习/实践经历
论文/成果/竞赛
校园经历
```

### Reason

For student biotech roles, education and project evidence are more relevant than generic self-evaluation and campus activities.

---

## 7. HTML/PDF Validation Checklist

Before claiming completion:

- HTML has print CSS.
- Text is selectable.
- Links work.
- No unresolved placeholders remain.
- PDF has no clipping.
- PDF has no overlap.
- PDF has no tiny unreadable text.
- Page breaks do not split headings from content.
- Short semantic tokens are not broken awkwardly.
- Requested files are linked.

If PDF fails:

```markdown
PDF 渲染失败，已提供 HTML 和 Markdown。当前不声称 PDF 已生成。
```
