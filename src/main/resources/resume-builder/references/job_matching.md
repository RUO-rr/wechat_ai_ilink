# Job Matching Overview

This file is the concise workflow entry. The authoritative schemas and scoring rules
are split across the job-intelligence-and-matching module.

## Workflow

1. Normalize every source using
   [job_source_rules.md](job_source_rules.md).
2. Extract atomic requirements using
   [job_requirement_extraction_schema.md](job_requirement_extraction_schema.md).
3. Convert the master profile to evidence records using
   [candidate_evidence_schema.md](candidate_evidence_schema.md).
4. Score requirement-evidence pairs using the 0-5 scale in
   [matching_scoring_rules.md](matching_scoring_rules.md).
5. Aggregate and classify experiences using
   [experience_selection_rules.md](experience_selection_rules.md).
6. Link final bullets using
   [traceability_rules.md](traceability_rules.md).

Stable ID prefixes are mandatory: job sources use `JS-###`, requirements use
`RQ-###`, candidate evidence uses `EV-###`, and final resume bullets use `BL-###`.
Each final bullet must have a hidden or separate trace record such as
`BL-001 -> EV-003, EV-007 -> RQ-002, RQ-005 -> JS-001`.

## Match Types

- `direct`: same problem, method, tool, deliverable, or responsibility.
- `adjacent`: closely related evidence with low adaptation cost.
- `transferable`: proves the underlying capability in another context.
- `none`: no defensible connection.

## Gaps

Classify gaps as:

- clarification gap;
- presentation gap;
- experience gap;
- eligibility gap;
- credibility risk.

Ask focused questions for clarification gaps. Never convert familiarity into expertise,
hide an eligibility failure, or insert unsupported keywords.

## Output

Use:

- `assets/templates/job_analysis_report.md`;
- `assets/templates/matching_matrix.md`;
- `assets/templates/evidence_trace_table.md`.
