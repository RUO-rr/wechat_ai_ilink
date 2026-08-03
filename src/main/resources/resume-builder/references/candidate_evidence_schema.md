# Candidate Evidence Schema

Convert the master profile into atomic, matchable evidence. One experience may produce
multiple evidence records.

## Schema

```json
{
  "evidence_id": "EV-001",
  "evidence_type": "education | research_project | work_experience | internship | skill | publication | patent | award | course | certificate | portfolio",
  "title": "",
  "time_range": "",
  "user_role": "",
  "description": "",
  "skills_used": [],
  "tools_used": [],
  "methods_used": [],
  "outputs": [],
  "quantitative_results": [],
  "proof_source": "user_provided | document | publication | certificate | portfolio | unknown",
  "proof_reference": "",
  "verification_status": "confirmed | needs_confirmation | conflicting",
  "resume_usable": true,
  "notes": ""
}
```

## Rules

- Use stable candidate evidence IDs: `EV-001`, `EV-002`, ...
- Never add facts not supplied by the user or source material.
- Mark uncertain values `needs_confirmation`.
- Split distinct capabilities, outputs, and outcomes when they match different
  requirements.
- Preserve dates, role, ownership, methods, tools, and result scope.
- Preserve publication author order, contribution, status, and year.
- Preserve patent application/publication/grant status and inventor order.
- Distinguish manuscript `in preparation`, `submitted`, `under review`, `accepted`,
  `in press`, and `published`.
- Set `resume_usable` false for confidential, unsupported, misleading, or
  user-excluded evidence.

Do not penalize or score evidence based on sex, age, marital/family status, ethnicity,
health, political affiliation, religion, disability, or other protected attributes.
