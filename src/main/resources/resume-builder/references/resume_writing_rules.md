# Resume Writing Rules

## Core Rules

- Keep content concise, scannable, truthful, and target-specific.
- Default students and recent graduates to one page.
- Allow one to two pages for experienced or research candidates when evidence warrants.
- Use a multi-page academic CV only when requested.
- Start bullets with strong, accurate action verbs.
- Use `action + task + method/tool + result/impact`.
- Quantify only verified outcomes; include units, baseline, scope, or timeframe.
- Prefer relevant evidence over completeness.
- When space is limited, follow
  [experience_selection_rules.md](experience_selection_rules.md) compression order:
  remove generic self-evaluation first, preserve eligibility-critical education,
  strongest target-role evidence, verified outputs, high-score project bullets, and
  mandatory technical skills with evidence.
- Normalize dates, capitalization, punctuation, titles, publications, patents, and
  awards.
- Use one consistent form for each institution, company, laboratory, method, software,
  instrument, and technical field. Do not alternate between full names and
  abbreviations unless the abbreviation is introduced once.
- Preserve official names for universities, companies, laboratories, journals, degrees,
  publications, patents, software, and instruments.
- Preserve placeholders or ask focused questions for missing critical information.
- In HTML output, keep short education/profile tokens intact. Wrap school, college,
  degree, major, location, name, company name, journal name, project name, method name,
  chemical/protein name, and short technical terms with a no-break span such as
  `<span class="keep-token">物理化学</span>` so the layout may wrap between fields but
  not inside the term.
- Keep Chinese-English mixed technical terms intact when possible, such as `LC-MS/MS`,
  `chemical proteomics`, `蛋白质组学`, and `click chemistry`.
- Avoid isolated punctuation at the beginning of a line and single orphan characters at
  the end of a line in Chinese resumes.
- For dense Chinese/English mixed bullet content, prefer CSS-controlled justification:
  `text-align: justify` with `text-align-last: left`. Do not add manual line breaks
  inside bullets unless they represent semantic separation. For HTML resumes, use CSS
  for spacing and alignment instead of hard-coded `<br>`.
- When delivering a near-final resume, provide content-level tuning room if requested:
  short, standard, and expanded versions of important bullets; required keywords;
  removable phrases; evidence IDs; and a user notes field. This allows wording changes
  without breaking factual traceability.

## Content Integrity

Avoid empty claims such as `性格开朗`, `吃苦耐劳`, `认真负责`, `results-driven`, or
`excellent communicator` without evidence.

Use ownership verbs precisely:

- `led/designed/主导/设计`: clear ownership;
- `implemented/executed/实施/完成`: hands-on delivery;
- `contributed/supported/参与/协助`: collaborative contribution.

Do not imply sole ownership of team results. Do not claim causation without evidence.
For research projects, avoid overstating independent ownership, listing techniques
without context, claiming publication status beyond evidence, or converting routine
coursework into research experience.

Flag risky content but do not automatically include it: low GPA, failed courses,
unfinished degrees, unexplained gaps, unrelated short experiences, weak low-relevance
awards or certificates, unverified claims, exaggerated proficiency, sensitive personal
information, and publications listed as accepted/submitted/in preparation without
evidence. Omit risky content when unnecessary, reframe it truthfully when relevant,
and mark it `需用户确认` when uncertain.

## ATS and Parser Compatibility

For ATS-oriented resumes:

- Avoid tables for core resume content unless an HTML/PDF visual version is separately
  requested.
- Use standard section headings: `Education`, `Experience`, `Projects`,
  `Publications`, `Skills`.
- Avoid icons, text boxes, multi-column layouts, and image-only text in ATS versions.
- Include exact role-relevant keywords only when supported by candidate evidence.
- Do not keyword-stuff unrelated tools or methods.
- Generate a plain Markdown or DOCX-friendly version when ATS compatibility is
  required.

## Language

Chinese resumes may provide slightly fuller context but must remain concise. Include
gender, political affiliation, native place, date of birth, photo, or address only
when supplied or explicitly requested. Do not infer age from graduation year unless
the user asks. Do not include political affiliation unless the user provides it. Keep
standard English names for methods, software, databases, journals, and standards.

English resumes must be restrained, ATS-friendly, idiomatic, and consistent in one
English variant. Default to no photo, no gender, no age, no marital status, no
political affiliation, and no full address. Use city/country only if location
relevance matters. Do not translate technical names literally.

Bilingual resumes are separately edited versions with aligned facts, not sentence-level
mirror translations.

For bilingual resumes, maintain a terminology map with Chinese term, English term,
abbreviation, and source or user confirmation.

## Ordering

Order sections by target decision value:

- students: education, relevant experience/projects, skills, outputs/awards;
- researchers: summary, selected research/professional experience, methods, education,
  selected outputs;
- experienced: summary, work experience, selected projects, skills, education;
- career changers: targeted summary, transferable evidence, relevant projects,
  experience, skills, education.

## Privacy and Regionalization

Chinese resumes may include user-supplied or explicitly requested gender, political
affiliation, native place, date of birth, photo, and address. International/English
resumes omit gender, political affiliation, photo, age, marital status, and full
address by default. Never add protected or sensitive fields merely because a template
contains them.
