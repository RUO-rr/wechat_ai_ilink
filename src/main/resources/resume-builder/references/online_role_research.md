# Online Role Research

## Contents

1. Research objective
2. Identity resolution
3. Source hierarchy
4. Search workflow
5. Evidence extraction
6. Synthesis and confidence
7. Failure modes

## Research Objective

Build a current, source-backed model of the exact role before comparing it with the
candidate. Research the work itself, not only keywords in one advertisement.

The output must answer:

- What will this person regularly do?
- What outcomes or deliverables are expected?
- Which knowledge, methods, tools, standards, and interpersonal capabilities matter?
- Which requirements are mandatory screens and which are preferences?
- What team, product, scientific area, or business context shapes the role?
- Which claims are explicit, corroborated, inferred, or unknown?

## Identity Resolution

Before searching, normalize:

- organization and business unit;
- exact job title and known aliases;
- location and remote/hybrid status;
- seniority or grade;
- requisition ID;
- posting URL and publication/closing dates;
- target language and country.

Avoid mixing similarly named roles, locations, subsidiaries, or seniority levels.
When multiple versions exist, use the requisition ID and employer page to identify the
authoritative posting.

## Source Hierarchy

Prefer sources in this order:

1. current employer careers page or official applicant tracking system;
2. official team, lab, product, research, technology, or company pages;
3. official publications, patents, conference materials, regulatory documents, or
   technical blogs that clarify the team's work;
4. other recent openings from the same organization and job family;
5. professional association or government occupational sources;
6. reputable job boards that reproduce the posting;
7. employee profiles, interviews, and third-party articles as contextual evidence;
8. generic career descriptions only as a low-confidence fallback.

Do not treat search snippets as final evidence. Open the source page. Do not use
anonymous forum claims as requirements.

## Search Workflow

### 1. Locate the exact posting

Search the official careers domain using the title, location, and requisition ID.
Record whether it is active, archived, unavailable, or duplicated.

### 2. Capture the explicit role

Extract the complete responsibilities, qualifications, preferred qualifications,
location, reporting/team context, and application constraints. Record short
paraphrases with source URLs and access date; avoid large verbatim copies.

### 3. Research the work context

Search official pages for the relevant team, platform, product, pipeline, therapeutic
area, research program, instruments, datasets, customers, or operating environment.
Look for expected outputs and stakeholders that the job posting may leave implicit.

### 4. Cross-check comparable openings

Inspect two to five recent, relevant openings when available:

- same title at the same employer;
- adjacent seniority in the same job family;
- same team or domain at the same employer.

Use them to identify recurring requirements, not to import every requirement into the
exact role. Keep company-specific evidence separate from general market evidence.

### 5. Resolve terminology

Expand abbreviations, normalize equivalent methods/tools, and distinguish broad
families from exact implementations. Example: do not equate all mass spectrometry
experience with a specific regulated LC-MS bioanalysis workflow.

### 6. Record freshness

For every source record:

- page title;
- organization/site;
- URL;
- publication date when available;
- access date;
- source type;
- relevance;
- status;
- evidence confidence.

## Evidence Extraction

Convert findings into atomic role requirements. Each item must include:

- normalized requirement or responsibility;
- exact role relevance;
- category: responsibility, deliverable, domain, method, tool, standard, behavioral,
  eligibility, or context;
- explicit, tentative, or market context status;
- mandatory, preferred, recurring, or contextual priority;
- supporting source IDs;
- confidence: high, medium, or low;
- notes on ambiguity or scope.

Evidence labels:

- `explicit`: supported by A, B, or C job sources;
- `tentative`: supported by D or E job sources and marked with source type;
- `market-context`: supported only by F sources, similar roles, or generic market
  information.

Only `explicit` items should materially drive resume selection by default.
`tentative` items require source labels and conservative wording. Never insert
`market-context` signals into the resume as if the employer requested them.

## Synthesis and Confidence

Create a role research summary with:

- five to ten principal work activities;
- required and preferred skill clusters;
- likely deliverables and stakeholders;
- recurring terminology;
- screening constraints;
- employer/team context;
- unresolved questions;
- source coverage and freshness;
- overall research confidence.

Confidence guidance:

- `high`: exact current official posting plus relevant official context;
- `medium`: archived/republished posting plus current official context or strong
  comparable postings;
- `low`: exact posting unavailable and analysis relies mainly on proxies.

The role research summary becomes the input to `job_matching.md`. Do not start resume
content selection before this synthesis is complete.

## Failure Modes

- If online research is unavailable, use the user-provided JD as the primary source,
  mark source confidence as limited, ask for an official link if needed, and do not
  invent missing requirements.
- If the posting is removed, search official archives and comparable current roles;
  label the analysis as proxy-based.
- If a site is blocked, use another official page or reputable reproduction and state
  the limitation.
- If sources conflict, prefer the exact current official posting and record the
  conflict.
- If the role is confidential or only described verbally, separate user-provided
  information from online findings.
- If current online information is sparse, do not fill gaps with assumptions. Ask the
  user or lower confidence.
