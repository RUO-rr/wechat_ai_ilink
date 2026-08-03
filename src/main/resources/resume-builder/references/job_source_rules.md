# Job Source Rules

## Input Types

Accept:

- official employer career or job pages;
- official company, department, team, lab, PI, research-group, or project pages;
- official recruitment PDFs and official public-account posts;
- LinkedIn, Indeed, Boss, Liepin, Zhaopin, and other job platforms;
- JD screenshots, recruitment posters, website screenshots, and public-account images;
- copied JD text, HR descriptions, recruitment email text, and user summaries.

For images, extract text with OCR/vision and retain the original file name or source
description. Do not infer an official URL from visual branding alone.

## Retrieval Priority

1. Search the exact role on the employer's official career site.
2. Search the target team, laboratory, department, PI, or research project.
3. Cross-check recent comparable roles at the same employer.
4. Use official LinkedIn/public-account/project pages as supporting context.
5. Use third-party job platforms as auxiliary reproductions.
6. Use market reports and occupational databases only for context.

Prefer same-company evidence over generic market descriptions.

## Source Record

Every source must include:

```json
{
  "source_id": "JS-001",
  "title": "",
  "url_or_description": "",
  "source_type": "official_job | official_ats | official_company | official_team | official_lab | official_department | official_product | official_project | official_pdf | official_recruiter_email | verified_official_account | job_platform | screenshot | poster | user_text | industry_reference | comparable_other_company_role",
  "organization": "",
  "publication_date": "",
  "access_date": "YYYY-MM-DD",
  "status": "active | archived | unavailable | unknown",
  "credibility": "A | B | C | D | E | F",
  "summary": "",
  "raw_text": ""
}
```

Use stable job source IDs: `JS-001`, `JS-002`, ...

Store only short necessary excerpts in reports. Preserve the original page/file
reference for audit.

## Job Source Confidence

- `A`: current official employer job page / official ATS posting.
- `B`: official team, lab, department, product, or project page.
- `C`: official recruiter email or verified official account post.
- `D`: reputable third-party job board reposting.
- `E`: user-provided screenshot, poster, or copied text without official verification.
- `F`: generic market information or similar roles from other companies.

Credibility is about the source's authority for this role, not whether its content
looks plausible.

## Requirement Support Rules

- `A`, `B`, and `C` may support explicit requirements.
- `D` and `E` may support tentative requirements and must be marked with source type.
- `F` may only support market context, not definitive role requirements.

## Conflict and Freshness

- Prefer a current exact A-level posting over every proxy.
- Preserve conflicts instead of merging them into vague language.
- Record access date and publication/closing date when available.
- Label removed postings and proxy research clearly.
- Do not treat search-result snippets as evidence; open the source.
- If only D-F sources exist, lower overall research confidence and ask for the
  original JD/link.
