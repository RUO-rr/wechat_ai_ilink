# candidate classification

This file defines how to classify the candidate, resume scenario, language market, and workflow before drafting, rewriting, researching, or generating files.

Classification is a routing step, not a user-facing questionnaire by default. Use available materials first. Ask the user only for missing information that changes the workflow or resume strategy.

## 1. Classification Output

Always produce an internal classification result before writing.

Recommended format:

```json
{
  "candidate_stage": "STUDENT | RECENT_GRADUATE | EXPERIENCED | CAREER_CHANGER | UNKNOWN",
  "candidate_modifiers": [
    "UNDERGRADUATE",
    "MASTER",
    "PHD",
    "RESEARCH_ORIENTED",
    "INDUSTRY_ORIENTED",
    "RETURNING_TO_ACADEMIA",
    "CROSS_DISCIPLINARY",
    "INTERNATIONAL_EDUCATION",
    "HAS_PUBLICATIONS",
    "HAS_WORK_EXPERIENCE"
  ],
  "resume_scenario": "INTERNSHIP_RESUME | CORPORATE_JOB_RESUME | ACADEMIC_CV | RESEARCH_CV | GENERAL_RESUME | UNKNOWN",
  "language_market": "CHINESE | ENGLISH | BILINGUAL | UNKNOWN",
  "workflow_mode": "QUICK_DIAGNOSIS | GENERAL_RESUME_BUILD | TARGETED_APPLICATION | TARGETED_REWRITE | FULL_DELIVERABLE_PACKAGE",
  "confidence": "HIGH | MEDIUM | LOW",
  "missing_decision_fields": []
}
```

## 2. Candidate Stage

Choose one primary candidate stage. Add modifiers for overlapping identities.

### STUDENT

Use when the candidate is currently enrolled in a degree program.

Common modifiers:

* `UNDERGRADUATE`
* `MASTER`
* `PHD`
* `RESEARCH_ORIENTED`
* `INDUSTRY_ORIENTED`
* `HAS_PUBLICATIONS`
* `HAS_INTERNSHIP_EXPERIENCE`

Default strategy:

* emphasize education, research/project training, methods, outputs, awards, competitions, publications, patents, and role-relevant skills;
* avoid overstating independent ownership;
* do not include weak or unrelated coursework unless it supports the target role.

### RECENT_GRADUATE

Use when the candidate graduated within the last 1–2 years and has limited full-time work experience.

Common modifiers:

* `INTERNATIONAL_EDUCATION`
* `HAS_INTERNSHIP_EXPERIENCE`
* `RESEARCH_ORIENTED`
* `INDUSTRY_ORIENTED`

Default strategy:

* keep education prominent;
* use internships, projects, thesis work, publications, and technical skills as core evidence;
* emphasize employability and role readiness.

### EXPERIENCED

Use when the candidate has 3+ years of full-time professional experience or clearly work-centered evidence.

Common modifiers:

* `HAS_MANAGEMENT_EXPERIENCE`
* `HAS_CROSS_FUNCTIONAL_EXPERIENCE`
* `RETURNING_TO_ACADEMIA`
* `RESEARCH_ORIENTED`

Default strategy:

* emphasize recent relevant roles, scope, results, tools, collaboration, leadership, and measurable impact;
* compress education unless it is highly relevant or prestigious;
* avoid leading with old student projects unless the role requires them.

### CAREER_CHANGER

Use when the candidate is moving from one field, function, or industry to another.

Common modifiers:

* `CROSS_DISCIPLINARY`
* `HAS_NEW_FIELD_TRAINING`
* `HAS_TRANSFERABLE_SKILLS`
* `HAS_ADJACENT_EXPERIENCE`

Default strategy:

* foreground transferable evidence;
* show new-field preparation through projects, coursework, certificates, publications, portfolios, or practical outputs;
* do not exaggerate direct experience in the target field;
* explicitly connect past evidence to target requirements.

### UNKNOWN

Use only when available information is insufficient.

Default strategy:

* inspect existing materials first;
* ask only the smallest set of questions needed to determine the workflow.

## 3. Research Intensity Modifier

Research identity should usually be a modifier, not always the primary candidate stage.

Use `RESEARCH_ORIENTED` when the candidate has one or more of the following:

* thesis or dissertation-centered experience;
* publications, manuscripts, patents, posters, conference abstracts, or research reports;
* laboratory, computational, clinical, field, or data research experience;
* research assistant, lab member, visiting student, or graduate researcher experience;
* target role values research capability.

For research-oriented candidates, project bullets should include:

* research question or objective;
* technical route;
* personal contribution;
* methods, instruments, models, datasets, or tools;
* result, output, publication, patent, thesis, poster, reproducible artifact, or validated finding;
* relevance to the target role.

## 4. Resume Scenario

Choose one primary resume scenario.

### INTERNSHIP_RESUME

Use when the target is an internship, trainee program, student program, summer research, industrial placement, or co-op.

Strategy:

* emphasize education, projects, technical skills, coursework only when relevant, internships, competitions, and learning speed;
* keep bullets concrete and evidence-backed.

### CORPORATE_JOB_RESUME

Use when the target is a company role, industry role, commercial organization, startup, hospital enterprise role, CRO/CDMO role, or non-academic job.

Strategy:

* emphasize role requirements, practical outputs, tools, collaboration, deliverables, business or technical impact;
* include publications only when relevant to the role.

### ACADEMIC_CV

Use when the target is academic evaluation, graduate school, scholarship, fellowship, academic visiting application, PI/lab application, or academic profile.

Strategy:

* allow more detail than a corporate resume;
* prioritize education, research experience, publications, presentations, grants, awards, teaching, service, and technical methods;
* preserve publication status accurately.

### RESEARCH_CV

Use when the target is a research institution, industrial research group, R&D scientist role, research assistant role, laboratory role, or translational research position.

Strategy:

* combine academic evidence with role-oriented writing;
* foreground research outputs, methods, technical platforms, reproducibility, and collaboration;
* compress generic self-evaluation.

### GENERAL_RESUME

Use when the user has no specific target role.

Strategy:

* build a clean reusable resume;
* avoid excessive targeting;
* create a master profile and identify missing information.

## 5. Language and Market

### CHINESE

Use when the resume is for Chinese-language delivery or mainland China-focused applications.

Rules:

* default to Chinese unless the user requests English or bilingual output;
* include gender, political affiliation, photo, native place, date of birth, or address only when supplied or explicitly requested;
* do not infer age, political affiliation, household registration, or marital status;
* prioritize education, school, major, research/project evidence, internships, awards, and role-relevant skills.

### ENGLISH

Use when the resume is for international applications, English-speaking employers, overseas universities, multinational companies, or English job descriptions.

Rules:

* omit gender, age, marital status, political affiliation, photo, and full street address by default;
* use city/country only when location matters;
* use ATS-compatible section headings when relevant;
* avoid literal translation from Chinese if it damages terminology or credibility.

### BILINGUAL

Use when the user explicitly asks for both Chinese and English versions.

Rules:

* create two independently polished versions;
* do not produce rigid line-by-line translation;
* preserve technical terms, publication status, institution names, and role titles accurately;
* adapt privacy conventions to each market.

## 6. Special Scenario Handling

| Scenario                                    | Classification                                                                    | Handling                                                              |
| ------------------------------------------- | --------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Current graduate student applying for jobs  | `STUDENT` + `MASTER/PHD` + target scenario                                        | Use student rules, but increase research/project weight               |
| Current PhD applying for R&D role           | `STUDENT` + `PHD` + `RESEARCH_ORIENTED` + `CORPORATE_JOB_RESUME` or `RESEARCH_CV` | Translate research into role-relevant technical evidence              |
| Recent graduate with strong publications    | `RECENT_GRADUATE` + `RESEARCH_ORIENTED`                                           | Keep education prominent and foreground research outputs              |
| Experienced candidate returning to academia | `EXPERIENCED` + `RETURNING_TO_ACADEMIA` + `ACADEMIC_CV`                           | Lead with recent experience, then connect to academic outputs         |
| Overseas education, applying in China       | candidate stage + `INTERNATIONAL_EDUCATION` + `CHINESE`                           | Highlight institution, degree, location, and cross-cultural relevance |
| Career changer                              | `CAREER_CHANGER` + relevant modifiers                                             | Lead with transferable skills and new-field evidence                  |
| Existing resume but no target role          | candidate stage + `GENERAL_RESUME`                                                | Diagnose and build a reusable version                                 |
| Existing resume plus JD                     | candidate stage + target scenario + `TARGETED_REWRITE`                            | Run job intelligence before rewriting                                 |
| User only asks for critique                 | candidate stage if inferable + `QUICK_DIAGNOSIS`                                  | Do not generate full files unless requested                           |

## 7. Workflow Selection

After classification, choose the smallest workflow that satisfies the request.

### QUICK_DIAGNOSIS

Use when:

* the user asks for review, critique, improvement suggestions, or problems in a resume.

Default output:

1. resume diagnosis;
2. missing information list;
3. prioritized revision suggestions.

### GENERAL_RESUME_BUILD

Use when:

* the user wants a resume but has no target role;
* the user wants to build a reusable profile.

Default output:

1. `career-master-profile.md`;
2. `general-resume.md` or `general-resume.html`;
3. `missing-info.md`.

### TARGETED_APPLICATION

Use when:

* the user provides a job URL, JD, screenshot, recruitment poster, company, team, lab, department, research group, or target role;
* the user asks whether they match a role;
* the user wants a resume for a specific application.

Default output:

1. `job-analysis-report.md`;
2. `matching-matrix.md`;
3. `targeted-resume.md` or `targeted-resume.html`;
4. `missing-info.md`.

### TARGETED_REWRITE

Use when:

* the user provides both an existing resume and a target role/JD.

Default output:

1. role requirement extraction;
2. evidence matching matrix;
3. experience selection decision;
4. optimized targeted resume.

### FULL_DELIVERABLE_PACKAGE

Use only when the user explicitly asks for files, final package, editable HTML, PDF, bilingual version, trace table, or tuning sheet.

Generate only when explicitly requested:

* `targeted-resume.pdf`;
* `content-tuning-sheet.md`;
* `evidence-trace-table.md`;
* bilingual version;
* photo prompt;
* full master profile JSON.

## 8. Ambiguity Rules

If multiple classifications apply:

1. choose the stage that best explains the candidate's current positioning;
2. preserve other identities as modifiers;
3. choose the resume scenario based on the target use case, not the candidate's identity;
4. choose the language market based on delivery context and user request.

Examples:

* A PhD student applying for an R&D scientist role is `STUDENT + PHD + RESEARCH_ORIENTED + CORPORATE_JOB_RESUME/RESEARCH_CV`.
* A recent graduate applying for a Chinese biotech role is `RECENT_GRADUATE + CHINESE + CORPORATE_JOB_RESUME`.
* A software engineer applying for a research master's program is `EXPERIENCED + RETURNING_TO_ACADEMIA + ACADEMIC_CV`.

## 9. Minimal Question Rule

Do not ask the user to complete the full classification form unless necessary.

Ask only for missing fields that affect:

* candidate stage;
* target scenario;
* language/market;
* whether job intelligence is required;
* whether file generation is required.

Preferred question style:

* “你这份简历主要用于投递实习、正式工作、科研岗位，还是学术申请？”
* “这份简历是中文投递中国岗位，英文投递国际岗位，还是需要中英双语？”
* “你是否已经有具体岗位/JD/公司链接？如果有，应先做岗位匹配再写简历。”

## 10. Classification Checklist

Before drafting, confirm internally:

* candidate stage is identified or marked `UNKNOWN`;
* important modifiers are preserved;
* resume scenario is identified;
* language market is identified;
* workflow mode is selected;
* missing decision fields are listed;
* targeted resumes trigger job intelligence before drafting.
