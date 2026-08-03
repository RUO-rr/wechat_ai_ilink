# Traceability Rules

Every key resume bullet must be auditable from role intelligence and personal evidence.

## Required Links

Record:

1. final resume bullet ID and content;
2. corresponding requirement ID;
3. requirement type: explicit, tentative, or market general;
4. job source ID/title/URL or description;
5. access date and source credibility;
6. candidate evidence ID and proof source;
7. match score, weight, coefficient, and composite score;
8. adoption decision;
9. rewrite reason;
10. verification status.

## Trace Record

```json
{
  "bullet_id": "BL-001",
  "resume_content": "",
  "trace_path": "BL-001 -> EV-003, EV-007 -> RQ-002, RQ-005 -> JS-001",
  "requirement_id": "RQ-001",
  "requirement_type": "explicit",
  "source_id": "JS-001",
  "job_source": "",
  "access_date": "YYYY-MM-DD",
  "source_credibility": "A",
  "evidence_id": "EV-001",
  "candidate_proof_source": "",
  "match_score": 5,
  "requirement_weight": 3,
  "composite_score": 15.0,
  "adoption_decision": "lead",
  "rewrite_reason": "",
  "verification_status": "confirmed"
}
```

## Rules

- Use stable resume bullet IDs: `BL-001`, `BL-002`, ...
- One bullet may link to multiple requirements/evidence records; create separate rows
  or arrays rather than hiding the many-to-many relationship.
- Every final bullet must have a stable `BL-###` ID and a hidden or separate trace
  record linking `BL-### -> EV-### -> RQ-### -> JS-###`.
- Do not put confidential personal proof into the outward-facing resume.
- Traceability tables are internal working artifacts unless the user requests them.
- If a bullet cannot be traced, revise or remove it.
- If evidence is `needs_confirmation`, keep the bullet out of the final application or
  mark it for user approval before delivery.
