# Output Quality Checklist

## Acceptance Criteria

A resume deliverable passes quality review only when all required gates are satisfied.

### Required Gates

- No fabricated facts.
- All uncertain facts are marked `待补充` or `需用户确认`.
- All final key bullets are supported by candidate evidence.
- For targeted resumes, all high-priority job requirements are either covered, marked
  as gaps, or explicitly excluded.
- No protected or market-sensitive personal information is included against the
  language/region rules.
- No unresolved placeholders remain.
- Requested files exist and are linked.
- PDF is not claimed unless rendered and visually checked.
- ATS version is parser-friendly when ATS compatibility is required.
- Missing critical information is listed separately from optional enhancement
  information.

### Recommended Gates

- One-page corporate resumes stay within the recommended density range.
- Bullets follow `action + task + method/tool + result/impact`.
- Terminology is consistent.
- Section order matches candidate type and target role.
- Repeated content is removed.
- Weak or risky content is either omitted, reframed, or marked for confirmation.

## Factual Integrity

- No fabricated experience, education, outputs, awards, skills, or metrics.
- Publication, patent, and award status matches evidence.
- Uncertain content is marked `待补充` or `需用户确认`.
- Every claim traces to a personal-profile source.
- Risky content is flagged rather than automatically included: low GPA, failed
  courses, unfinished degrees, unexplained gaps, unrelated short experiences, weak
  low-relevance awards/certificates, unverified claims, exaggerated proficiency,
  sensitive personal information, and unsupported accepted/submitted/in-preparation
  publications.

## Publications and Patents

- First-author and co-first-author publications are ranked before collaborative papers
  when relevant to the target role.
- Within the same authorship tier, publications are ranked by target-role relevance.
- Collaborative papers are ranked after first-author/co-first-author papers unless
  they are uniquely relevant to a high-priority role requirement.
- Publication format is consistent across the resume.
- Authorship role is explicit when it affects evaluation: first author, co-first
  author, corresponding author, co-author.
- Publication status is precise: `已发表`, `已接收`, `同行评审中`, `投稿待审中`,
  `手稿准备中`, `预印本`, or `需用户确认`.
- English publication status is precise: `Published`, `Accepted`,
  `Under peer review`, `Submitted, pending editorial screening`,
  `Manuscript in preparation`, `Preprint`, or `Needs user confirmation`.
- Chinese resumes use CAS journal partition when available and relevant.
- English resumes use JCR quartile when available and relevant.
- CAS/JCR information is verifiable and includes the system, edition/year, applicable
  category, source, and access date, or is marked `待确认`.
- CAS/JCR partition data is never filled from memory, journal reputation, impact
  factor, or an impression of the journal.
- Unpublished manuscripts are not described as published, accepted, or under review
  unless supported.
- Patent applications are not described as granted patents unless grant information
  is verified.
- Missing DOI, author order, journal, year, patent number, legal status, or
  partition/quartile is marked `待补充` or `需用户确认`.
- Online-completed publication or patent fields are supported by source records.
- Examples and templates do not contain user-related private publication, patent,
  institution, collaborator, or manuscript information.

## Targeting

- Exact role was researched online or clearly marked as a proxy analysis.
- Sources include URL, access date, type, freshness, and confidence.
- Principal duties and skill clusters are captured.
- Selected evidence covers high-priority requirements.
- Keywords are used only when supported.
- Gaps and eligibility risks are visible.
- ATS/parser versions use standard headings, no core-content tables, no icons, no text
  boxes, no multi-column layout, and no image-only text.
- ATS/parser versions are available as plain Markdown or DOCX-friendly content when
  ATS compatibility is required.

## Consistency

- No timeline conflicts or unexplained duplicate records.
- Dates, titles, punctuation, capitalization, and citation formats are consistent.
- Institutions, companies, laboratories, methods, software, instruments, and technical
  fields use one consistent form; abbreviations are introduced once before reuse.
- Official names for universities, companies, laboratories, journals, degrees,
  publications, patents, software, and instruments are preserved.
- Bilingual resumes include or maintain a terminology map with Chinese term, English
  term, abbreviation, and source or user confirmation.
- Repeated content is removed.
- Self-evaluation is evidence-based.
- When space is limited, compression follows the defined order and preserves
  eligibility-critical education, strongest target-role evidence, verified outputs,
  high-score project bullets, and mandatory technical skills with evidence.
- Missing-information list is focused and current.

## Regional and Language Rules

- Chinese/English/bilingual scenario is explicit.
- Chinese resumes include gender, political affiliation, native place, date of birth,
  photo, or address only when supplied or explicitly requested.
- Age is not inferred from graduation year unless the user asks.
- English resumes omit photo, gender, age, marital status, political affiliation, and
  full address by default; city/country appears only when location relevance matters.
- English is idiomatic and ATS-friendly.
- Bilingual versions preserve facts without literal awkward translation.

## Layout and Files

- Section order matches candidate type and target.
- Page length and density are appropriate.
- One-page Chinese resumes are usually 700-1200 Chinese characters excluding contact
  information; each project/experience section usually has 2-4 bullets; each bullet is
  usually 35-80 Chinese characters; rendered paragraphs do not exceed 3 lines; one
  experience does not exceed 6 bullets unless the user asks for a detailed CV.
- One-page English resumes are usually 450-750 words; bullets are usually 12-28 words;
  each experience/project entry usually has 2-5 bullets; dense paragraphs are avoided.
- Academic CVs may exceed one page and may expand publication, patent, presentation,
  and teaching sections when the target is academic evaluation.
- HTML contains no unresolved placeholders and has print CSS.
- Links work and text remains selectable.
- PDF, when requested, has been visually checked for clipping, overlap, tiny text,
  whitespace, and page-break problems.
- If PDF rendering fails, HTML and Markdown are provided, the failure is reported, and
  the PDF is not claimed as generated.
- If scripts were unavailable, equivalent schema transformation was performed manually
  and the response clearly states that script validation was not run.
- Education and headline rows do not break inside short semantic tokens such as school,
  college, degree, major, city, name, company name, journal name, project name, method
  name, chemical/protein name, or technical term. For example, `物理化学`, `LC-MS/MS`,
  `chemical proteomics`, `蛋白质组学`, and `click chemistry` must remain intact; if
  space is insufficient, move the whole token group to the next line.
- Lines avoid isolated punctuation at the beginning and single orphan characters at the
  end in Chinese resumes.
- Main content bullets and skill descriptions use consistent alignment. Dense
  Chinese/English mixed bullets should be justified with the last line left-aligned, or
  explicitly set to left alignment if justification creates awkward spaces.
- Bullets do not contain manual `<br>` line breaks used only for visual alignment; HTML
  resumes use CSS for spacing and alignment instead of hard-coded `<br>`.
- A user-editable HTML version is provided when the user asks to fine tune layout.
- A content tuning sheet is provided when the user asks for wording/content
  micro-adjustment room. It must keep requirement IDs, evidence IDs, editable wording
  alternatives, removable phrases, required keywords, and user notes.

## Delivery

- Information completeness diagnosis is included.
- Missing information is included.
- Missing information is split into current profile summary, missing critical fields,
  and optional enhancement fields.
- The user is not asked for all missing information at once; questions are limited to
  information that changes the resume decision.
- Positioning and role-match advice is included.
- Requested resume files are linked.
- Optional variants are identified without unnecessary duplication.
- User-editable content and layout tuning artifacts are linked when requested.
- Photo advice/prompt appears only when relevant.
- Next optimization actions are concrete.

## Completion Criteria

Complete the task only when all applicable criteria are met:

### Classification and Scope

- Candidate stage is classified or marked `UNKNOWN`.
- Resume scenario is classified.
- Language and market are explicit.
- Workflow mode is selected.
- Missing decision-critical information is listed.

### Evidence Integrity

- No fabricated education, experience, metrics, publications, patents, awards, dates,
  authorship, skills, or proficiency.
- Uncertain facts are marked `待补充` or `需用户确认`.
- Risky or unfavorable information is flagged instead of automatically included.
- Master profile and targeted resume remain separate.

### Targeted Resume Requirements

- Job source, requirement, candidate evidence, match score, adoption decision, and final
  bullet are traceable.
- Job sources include URL, access date, source type, freshness, and confidence when
  online research is used.
- Explicit requirements, tentative requirements, and generic market signals are
  separated.
- High-priority requirements are covered or listed as gaps.

### Content Quality

- Section order matches candidate type and target.
- Experience bullets use `action + task + method/tool + result/impact`.
- Repeated content is removed.
- Self-evaluation is evidence-based.
- Keywords are included only when supported by evidence.
- Terminology is consistent.

### Layout and Format

- No unresolved placeholders remain.
- HTML contains print CSS when HTML is requested.
- PDF is generated only when requested.
- PDF is visually checked before being claimed as complete.
- ATS-compatible version avoids core-content tables, icons, text boxes, multi-column
  layout, and image-only text when ATS compatibility is required.

### Delivery

- Requested files are generated and linked.
- Missing information is split into critical fields and optional enhancement fields.
- Next optimization actions are specific.
- Optional outputs are not generated unless requested.
