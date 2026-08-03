<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Research CV Case — PhD Candidate

> 示例目的：展示科研型候选人的项目写法，强调研究问题、技术路线、个人贡献、输出和可复现性。  
> 本案例为虚构示范。

## 1. Scenario

Candidate:

- Current PhD student in chemical biology.
- Applying for an R&D scientist / research associate role.
- Has LC-MS/MS, chemical proteomics, probe design, and bioinformatics experience.
- Has one manuscript in preparation and one conference poster.

## 2. Internal Classification

```json
{
  "candidate_stage": "STUDENT",
  "candidate_modifiers": ["PHD", "RESEARCH_ORIENTED", "HAS_PUBLICATIONS"],
  "resume_scenario": "RESEARCH_CV",
  "language_market": "ENGLISH",
  "workflow_mode": "TARGETED_APPLICATION",
  "confidence": "HIGH",
  "missing_decision_fields": ["publication_status_confirmation", "target_role_url"]
}
```

## 3. Research Experience Structure

For research CVs, each major project should answer:

1. What scientific problem was addressed?
2. What technical route was used?
3. What was the candidate's personal contribution?
4. What methods, instruments, tools, or datasets were involved?
5. What output was produced?
6. Why is it relevant to the target role?

## 4. Bad Research Bullet

```markdown
- Participated in chemical proteomics research and used LC-MS/MS to study protein modifications.
```

### Why it fails

- No research question.
- No technical route.
- No personal contribution.
- No output.
- No evidence of depth.
- Too generic for a PhD-level candidate.

## 5. Better Research Bullet

```markdown
- Developed a chemical-proteomics data analysis workflow to characterize methionine-associated labeling events from LC-MS/MS search outputs, integrating peptide-level filtering, site-level aggregation, protein-level summarization, and motif-frequency analysis.
```

### Why it passes

- Defines the technical problem.
- Shows the candidate's contribution.
- Names the data type and workflow.
- Provides concrete analysis layers.
- Is credible without overstating publication impact.

## 6. Full Research Project Entry

### Methionine Chemical Proteomics and Site-Level Data Analysis

**Research objective:** Investigated whether methionine-associated chemical labeling patterns reveal sequence or protein-level preferences in complex proteomic samples.

**Technical route:** Combined chemical labeling, LC-MS/MS database search outputs, site-level filtering, and downstream bioinformatics analysis.

**Candidate contribution:**

- Processed pFind-derived spectra and site tables to identify modified peptides, modified proteins, and non-redundant site counts across multiple sample groups.
- Built reproducible Python scripts to clean modification tables, standardize protein identifiers, merge replicate-level outputs, and prepare publication-ready summary tables.
- Performed motif-window extraction around modified methionine sites and compared amino-acid frequency patterns against a defined proteome-level background.
- Generated Venn diagrams, frequency heatmaps, and summary statistics to compare labeling overlap and sample-specific enrichment patterns.
- Documented the data-processing method for inclusion in a manuscript methods section and internal reproducibility notes.

**Outputs:**

- Reproducible analysis scripts;
- curated site/protein summary tables;
- motif-frequency visualizations;
- conference poster draft;
- manuscript section in preparation, status requires confirmation.

## 7. Publication Status Rules

### Bad

```markdown
Manuscript accepted by Nature Chemical Biology.
```

if the user only said "准备投稿".

### Good

```markdown
Manuscript in preparation, title and author order pending confirmation.
```

or

```markdown
Conference poster, presented at [conference name], date and title require confirmation.
```

## 8. Research CV Section Order

Recommended for research-oriented PhD candidate:

1. Header
2. Research Profile / Summary
3. Education
4. Research Experience
5. Publications and Manuscripts
6. Conference Presentations
7. Technical Skills
8. Awards / Grants
9. Teaching / Mentoring / Service, if relevant

For corporate R&D roles, consider moving `Technical Skills` before `Publications` if the JD emphasizes methods and platforms.

## 9. Missing Information

Critical:

- Exact degree, institution, department, advisor, and expected graduation date.
- Publication status: published / accepted / under review / submitted / in preparation.
- Exact personal contribution to experimental design, wet-lab work, data analysis, and writing.
- Target role or research group.

Optional:

- ORCID, Google Scholar, portfolio, GitHub.
- Selected poster or conference details.
- Instrument models, software versions, database search parameters.
