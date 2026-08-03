# Publications and Patents Rules

This file defines how to format, rank, verify, and present publications, manuscripts,
patents, and patent applications in Chinese, English, or bilingual resumes/CVs.

Use this file whenever the candidate provides papers, manuscripts, preprints, patents,
patent applications, invention disclosures, conference papers, posters, or
publication-related claims.

## 1. Non-Negotiable Rules

- Never invent papers, patents, authorship, author order, journal names, DOI, patent
  numbers, publication status, acceptance status, journal quartile, impact factor,
  citations, or indexing status.
- Journal partition and quartile data must be verifiable from a named source. Never
  fill CAS partition or JCR quartile from memory, general reputation, impact factor,
  or an impression of the journal.
- Preserve the exact status of each publication or patent.
- Mark uncertain or incomplete information as `待补充` / `需用户确认`.
- Do not upgrade `manuscript in preparation` to `submitted`, `under review`,
  `accepted`, or `published`.
- Do not upgrade `patent application` to `granted patent`.
- Do not infer first authorship or corresponding authorship unless explicitly
  supported.
- Do not include private manuscripts, unpublished titles, confidential patent details,
  or sensitive research content unless the user explicitly supplies them and permits
  inclusion.
- Examples in this skill must use fictional or unrelated information and must not
  reveal the user's personal research, publications, patents, institutions,
  collaborators, or unpublished work.

## 2. Publication Ranking Rules

Rank publications by authorship role first, then relevance to the target role, then
publication status, then journal/venue strength.

### 2.1 Authorship Priority

Use this order by default:

1. First-author papers;
2. Co-first-author papers;
3. Corresponding-author papers, if the candidate is a senior/experienced/research
   candidate;
4. Major-contribution papers where the candidate is not first author but the work is
   highly relevant;
5. Other collaborative papers;
6. Posters, abstracts, conference presentations, and non-peer-reviewed outputs.

For student, recent graduate, and early-career candidates:

- first-author and co-first-author papers should usually appear before collaborative
  papers;
- collaborative papers should be included only if space allows or if strongly relevant
  to the target role;
- unrelated collaborative papers should be compressed or omitted from a targeted
  corporate resume.

For experienced researchers or PI-level candidates:

- corresponding-author role may be weighted more heavily;
- group leadership and senior authorship may be relevant;
- do not apply student-style ordering mechanically.

### 2.2 Relevance Override

Within the same authorship tier, sort by relevance to the target role.

For targeted resumes:

1. Most relevant first-author / co-first-author papers;
2. Other relevant first-author / co-first-author papers;
3. Highly relevant collaborative papers;
4. Less relevant collaborative papers;
5. Unrelated papers, omitted or moved to a complete CV.

For academic CVs:

- include more complete publication records;
- still group by status and authorship clearly;
- avoid hiding lower-authorship papers, but do not let them obscure the strongest
  evidence.

### 2.3 Recommended Grouping

For concise resumes:

```markdown
Selected Publications
```

Use selected publications only. Do not include a full list unless requested or
appropriate for an academic CV.

For academic CVs:

```markdown
Peer-Reviewed Publications
Manuscripts
Preprints
Conference Papers / Posters
Patents
```

For corporate R&D resumes:

```markdown
Selected Publications and Patents
```

Use only outputs that support the target role.

## 3. Publication Status Vocabulary

Use precise status labels.

### Chinese Status Labels

- `已发表`
- `已接收`
- `同行评审中`
- `投稿待审中`
- `手稿准备中`
- `预印本`
- `会议摘要`
- `会议海报`
- `需用户确认`

### English Status Labels

- `Published`
- `Accepted`
- `Under peer review`
- `Submitted, pending editorial screening`
- `Manuscript in preparation`
- `Preprint`
- `Conference abstract`
- `Conference poster`
- `Needs user confirmation`

### Status Rules

Use `手稿准备中 / Manuscript in preparation` when:

- the manuscript is being written, revised internally, or prepared for submission;
- no journal submission has occurred;
- journal name, author order, or title is not finalized.

Use `投稿待审中 / Submitted, pending editorial screening` when:

- the paper has been submitted;
- there is no confirmation that it has entered external peer review;
- editorial screening is ongoing or unknown.

Use `同行评审中 / Under peer review` when:

- the journal has confirmed external peer review;
- the user supplies evidence or clearly states the paper is under review.

Use `已接收 / Accepted` when:

- acceptance is confirmed by the journal;
- do not use this label for `minor revision`, `conditionally accepted`, or
  `recommended for acceptance` unless the evidence confirms acceptance.

Use `已发表 / Published` when:

- the paper has a final publication record, DOI, volume/issue/pages, or online
  publication page.

## 4. Required Publication Fields

For each paper, collect and preserve:

- title;
- authors in correct order;
- candidate authorship role;
- journal or venue;
- year;
- status;
- DOI or URL, if available;
- journal partition/quartile, if requested or relevant;
- source used to verify the record;
- whether the record is verified, user-supplied, or incomplete.

Recommended internal schema:

```json
{
  "type": "journal_article | manuscript | preprint | conference_paper | poster | abstract",
  "title": "",
  "authors": [],
  "candidate_role": "first_author | co_first_author | corresponding_author | co_author | unknown",
  "journal_or_venue": "",
  "year": "",
  "status": "published | accepted | under_peer_review | submitted_pending_screening | manuscript_in_preparation | preprint | conference_poster | unknown",
  "doi": "",
  "url": "",
  "quartile_system": "CAS | JCR | none | unknown",
  "quartile": "",
  "source": "",
  "verification_status": "verified | user_supplied | incomplete | needs_confirmation",
  "notes": ""
}
```

## 5. Journal Partition / Quartile Rules

### Verification Requirement

Treat partition and quartile information as edition-specific source data, not as a
permanent attribute of a journal.

For every CAS partition or JCR quartile used in a resume, record:

- the partition system: `CAS` or `JCR`;
- the edition or data year;
- the category used for JCR, when available;
- the broad and/or narrow category used for CAS, when available;
- the official or authoritative lookup source;
- the access date;
- whether the value was verified, user-supplied, or remains unconfirmed.

Never infer a partition or quartile from impact factor, journal prestige, a previous
year's value, another category's value, a search-result snippet, or memory. If the
value cannot be checked, omit it or label it `待确认` / `pending confirmation`.

### Chinese Resumes

For Chinese resumes, use the Chinese Academy of Sciences journal partition when
available and relevant.

The CAS Journal Ranking Tables are a research output of the National Science Library,
Chinese Academy of Sciences. The [official platform](https://www.fenqubiao.com/)
states that the tables were first released in 2004. As of 2026, the platform also
states that the National Science Library will no longer update or publish the tables
from 2026 onward. Therefore, always preserve the exact historical edition year and
never present an older CAS partition as an automatically current value.

Preferred label:

```markdown
中科院分区：大类一区 / 小类二区
```

or, if only broad information is available:

```markdown
中科院分区：一区，具体大类/小类待确认
```

If unavailable:

```markdown
中科院分区待确认
```

Do not invent CAS partition data.

### English Resumes

For English resumes, use JCR quartile when available and relevant.

JCR quartiles are category rankings in Clarivate Journal Citation Reports. Clarivate
defines Q1-Q4 from a journal's rank within a category, with Q1 representing the
highest-ranked interval. Verify the category and JCR edition/year through
[Clarivate's JCR record or documentation](https://support.clarivate.com/ScientificandAcademicResearch/s/article/Journal-Citation-Reports-Quartile-rankings-and-other-metrics).
A journal can have different quartiles across categories or years, so never report
`JCR Q1-Q4` without checking the applicable category and edition.

Preferred label:

```markdown
JCR Q1
```

or:

```markdown
JCR Q1 in [category], year/source pending confirmation
```

If unavailable:

```markdown
JCR quartile pending confirmation
```

Do not invent JCR quartile, impact factor, category, or year.

### Bilingual Resumes

For bilingual resumes:

- Chinese version: default to CAS partition if available;
- English version: default to JCR quartile if available;
- do not mechanically translate CAS partition into English unless the target audience
  understands it;
- do not show both CAS and JCR unless the user requests it or the target context
  benefits from both.

## 6. Recommended Citation Formats

Use one consistent format within each resume.

### Chinese Resume Format

For published papers:

```markdown
作者. 题目. 期刊, 年份, 卷(期): 页码. DOI. 论文状态：已发表；作者贡献：第一作者；中科院分区：大类一区/小类一区。
```

Concise Chinese resume format:

```markdown
第一作者，题目，期刊，年份，DOI，中科院分区：一区。
```

For unpublished manuscripts:

```markdown
作者. 题目. 状态：手稿准备中 / 投稿待审中 / 同行评审中 / 已接收。作者贡献：第一作者。期刊信息：待补充 / 已投稿至[期刊名，需用户确认]。
```

### English Resume Format

For published papers:

```markdown
Authors. "Title." Journal, Year, Volume(Issue), Pages. DOI. Status: Published. Role: First author. JCR: Q1.
```

Concise English resume format:

```markdown
First author, "Title," Journal, Year, DOI, JCR Q1.
```

For unpublished manuscripts:

```markdown
Authors. "Title." Status: Manuscript in preparation / Submitted, pending editorial screening / Under peer review / Accepted. Role: First author. Journal information pending confirmation.
```

## 7. Patent Rules

Use precise patent status.

### Chinese Patent Status Labels

- `已授权发明专利`
- `发明专利申请`
- `实用新型专利`
- `外观设计专利`
- `PCT申请`
- `公开中`
- `审查中`
- `已驳回`
- `已放弃`
- `需用户确认`

### English Patent Status Labels

- `Granted invention patent`
- `Patent application`
- `Utility model`
- `Design patent`
- `PCT application`
- `Published application`
- `Under examination`
- `Rejected`
- `Abandoned`
- `Needs user confirmation`

### Required Patent Fields

Collect and preserve:

- patent title;
- inventors in correct order;
- candidate role;
- application number;
- publication number;
- grant number, if granted;
- filing date;
- publication date;
- grant date, if granted;
- jurisdiction;
- assignee/applicant;
- legal status;
- source used for verification;
- verification status.

Recommended internal schema:

```json
{
  "type": "invention_patent | patent_application | utility_model | design_patent | pct_application",
  "title": "",
  "inventors": [],
  "candidate_role": "first_inventor | co_inventor | unknown",
  "application_number": "",
  "publication_number": "",
  "grant_number": "",
  "filing_date": "",
  "publication_date": "",
  "grant_date": "",
  "jurisdiction": "",
  "assignee_or_applicant": "",
  "legal_status": "",
  "source": "",
  "verification_status": "verified | user_supplied | incomplete | needs_confirmation",
  "notes": ""
}
```

### Chinese Patent Format

```markdown
发明人. 专利名称. 专利类型，申请号：CNXXXXXXXXX，公开号：CNXXXXXXXXX，授权号：CNXXXXXXXXX，状态：已授权/审查中，日期：YYYY-MM-DD。
```

Concise version:

```markdown
第一发明人，专利名称，已授权发明专利，授权号：CNXXXXXXXXX。
```

### English Patent Format

```markdown
Inventors. "Patent Title." Patent type, application no., publication no., grant no., jurisdiction, status, date.
```

Concise version:

```markdown
First inventor, "Patent Title," granted invention patent, CNXXXXXXXXX.
```

## 8. Online Completion Rules

If paper or patent information is incomplete, online lookup is allowed when the user
has provided enough identifying information.

### For Papers

Search in this order when possible:

1. DOI;
2. title exact match;
3. title + first author;
4. journal + year + author;
5. PubMed / Crossref / publisher page / Google Scholar / Web of Science / journal
   website, depending on access.

Complete only verifiable fields:

- title;
- author order;
- journal;
- year;
- DOI;
- publication status;
- volume/issue/pages;
- official URL;
- JCR quartile, if accessible;
- CAS partition, if accessible.

If sources conflict, report the conflict and mark the field `需用户确认`.

### For Patents

Search in this order when possible:

1. patent number;
2. application number;
3. title exact match;
4. inventor + title keywords;
5. for Chinese patents, the
   [CNIPA Patent Search and Analysis System](https://pss-system.cponline.cnipa.gov.cn/);
6. for international applications and PCT records,
   [WIPO PATENTSCOPE](https://patentscope.wipo.int/);
7. the relevant national or regional patent office database, such as USPTO or EPO;
8. [Google Patents](https://patents.google.com/) as a convenient discovery and
   cross-search entry point.

Prefer official patent-office or WIPO records for the final legal status. Google
Patents may be used to locate records and compare metadata, but do not rely on it
alone to confirm grant, expiration, abandonment, withdrawal, examination, or other
current legal status when an official register is available.

Complete only verifiable fields:

- title;
- inventor order;
- assignee/applicant;
- application number;
- publication number;
- grant number;
- filing date;
- publication date;
- grant date;
- jurisdiction;
- legal status.

If sources conflict, report the conflict and mark the field `需用户确认`.

## 9. Relevance-Based Selection

For targeted resumes, each publication or patent must be classified as:

- `lead`: directly supports high-priority role requirements;
- `support`: relevant but not central;
- `omit`: unrelated, low-relevance, too detailed, or space-inefficient.

Do not include papers solely because the journal is prestigious. Relevance to the
target role is required.

### Selection Priority for Targeted Resumes

1. First-author / co-first-author output directly relevant to the target role;
2. Patent directly relevant to the target technology or product area;
3. Collaborative paper directly relevant to the role;
4. Strong publication in adjacent field;
5. Unrelated paper or patent, omitted or moved to full CV.

## 10. Examples

The following examples are fictional and unrelated to any user.

### Chinese Published Paper Example

```markdown
第一作者：Li X, Zhang Y. 基于城市热岛效应的屋顶反照率调控模型. Sustainable Cities and Society, 2024, 105: 105321. DOI: 10.0000/example.2024.105321. 状态：已发表；中科院分区：大类二区（具体小类待确认）。
```

### English Published Paper Example

```markdown
Li X, Zhang Y. "A roof-albedo optimization model for urban heat-island mitigation." Sustainable Cities and Society, 2024, 105:105321. DOI: 10.0000/example.2024.105321. Status: Published; Role: First author; JCR: Q1 pending source confirmation.
```

### Chinese Manuscript Example

```markdown
第一作者：Wang M, Chen L. 面向钠离子电池硬碳负极的孔结构调控策略. 状态：手稿准备中；目标期刊与作者顺序待确认。
```

### English Manuscript Example

```markdown
Wang M, Chen L. "Pore-structure regulation strategies for hard-carbon anodes in sodium-ion batteries." Status: Manuscript in preparation; target journal and author order pending confirmation.
```

### Chinese Patent Example

```markdown
第一发明人：Chen L, Liu Q. 一种低温固化水性防腐涂层及其制备方法. 发明专利申请，申请号：CN2024XXXXXXXX.X，状态：审查中，申请日：2024-05-18。
```

### English Patent Example

```markdown
Chen L, Liu Q. "A low-temperature curable waterborne anticorrosive coating and preparation method." Patent application, CN2024XXXXXXXX.X, status: under examination, filed May 18, 2024.
```

## 11. Quality Check

Before final delivery, verify:

- authorship role is correct;
- first-author and co-first-author papers are not buried below less relevant
  collaborative papers;
- collaborative papers are sorted by relevance and compressed when necessary;
- publication status is precise;
- unpublished manuscripts are not overstated;
- CAS partition is used for Chinese resumes when available;
- JCR quartile is used for English resumes when available;
- missing DOI, journal, year, patent number, legal status, or author order is marked;
- online-completed fields cite or record their source;
- fictional examples do not contain user-related private information.
