# Information Intake Schema

Collect progressively. Each field supports `value`, `source`, `confidence`,
`verification_status`, and `allowed_use`. Use `待补充` rather than guessing.

Do not ask for all missing information at once. First use available materials to
produce:

1. current profile summary;
2. missing critical fields;
3. optional enhancement fields.

Only ask for information that changes the resume decision.

## Candidate and Scenario

- candidate type: student, recent graduate, researcher, experienced, career changer;
- resume type: internship, corporate, research, academic CV;
- language: Chinese, English, bilingual;
- target role, industry, organization, city/country;
- application deadline, page limit, required format;
- emphasis, exclusions, privacy and photo preferences.

## Personal Information

- preferred name and legal/display variants;
- gender, phone, email, city, optional contact address;
- native place and date of birth only when supplied or explicitly requested;
- never infer age from graduation year unless the user asks;
- WeChat, LinkedIn, GitHub, ORCID, Google Scholar, portfolio/site;
- political affiliation only when the user provides it;
- English/other language level and certification;
- academic performance, GPA/rank;
- strengths supported by evidence;
- career objective and interests;
- location, relocation, remote, availability, work authorization.

## Education

For each record:

- school, college/department, major, degree;
- location and start/end dates;
- GPA/rank and grading scale;
- core courses relevant to the target;
- supervisor, laboratory/research group;
- thesis/research topic;
- exchange, joint training, visiting study;
- scholarships, honors, evidence source.

## Employment, Internship, Research, and Projects

For each record:

- organization/company/lab and role;
- dates, location, employment type;
- problem, hypothesis, task, or business need;
- methods, tools, instruments, platforms, data, standards;
- owned module and exact personal contribution;
- collaborators, stakeholders, team size, scope;
- quantified result where verified;
- qualitative impact or decision enabled;
- final output: product, report, protocol, paper, patent, dataset, software, presentation;
- evidence source and reusable bullet variants.

## Skills

Student/research focus:

- academic/domain knowledge;
- experimental methods and instruments;
- data analysis, statistics, bioinformatics/computational methods;
- software, programming, databases;
- language and evidence-backed general capabilities.

Experienced-candidate focus:

- role-specific skills;
- project/people management;
- business/domain capability;
- tools and platforms;
- analytics and reporting;
- cross-functional collaboration and stakeholder work.

Record evidence/project IDs and last-used date for every important skill.

## Outputs

- publications and exact status;
- patents and application/grant status;
- software copyrights;
- conference talks and posters;
- research grants/projects;
- datasets, open-source work, portfolios;
- full title/citation, date, identifier/URL, authorship and contribution.

Never convert `preparing`, `submitted`, or `under review` into `published`.

## Awards

- scholarship;
- academic competition;
- innovation/entrepreneurship award;
- conference award;
- honor title;
- school/department recognition;
- company/internship recognition;
- awarding body, level, date, selection scope, evidence.

## Self-Evaluation and Career Interests

Derive only after factual intake:

- capability;
- supporting evidence ID;
- target-role relevance;
- preferred problems, domains, environments, and growth direction.

Reject unsupported personality lists.

## Completeness Levels

- `critical`: identity/contact permission, target, chronology, strongest evidence;
- `important`: results, ownership, methods, outputs, target-critical skills;
- `optional`: full course lists, hobbies, secondary awards, detailed address.

Proceed when critical fields and target-relevant important fields are sufficient.
Return a focused missing-information list rather than blocking on optional details.
Separate missing critical fields from optional enhancement fields.
