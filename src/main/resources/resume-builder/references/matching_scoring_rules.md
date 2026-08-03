# Matching Scoring Rules

Score each candidate evidence item against each job requirement.

## Requirement Weight

- `5`: mandatory / eligibility-critical.
- `4`: strongly preferred.
- `3`: relevant but not central.
- `2`: weakly relevant.
- `1`: background signal.

When category and importance conflict, choose the weight that reflects actual hiring
importance and explain it.

## Match Score

- `5`: direct, recent, evidence-backed match.
- `4`: direct but less recent or less quantified.
- `3`: adjacent evidence.
- `2`: transferable but indirect.
- `1`: weak keyword-level similarity.
- `0`: no evidence.

## Credibility Coefficient

- `1.0`: verified personal document, publication, transcript, certificate,
  portfolio, or resume evidence.
- `0.8`: user-stated but plausible and internally consistent.
- `0.6`: inferred from surrounding experience.
- `0.4`: uncertain, must be marked `需用户确认`.

## Composite Score

`composite_score = requirement_weight * match_score * credibility_coefficient`

Keep requirement weight, match score, credibility coefficient, and composite score
visible. Do not report the composite as a hiring probability.

## Matching Matrix Record

```json
{
  "requirement_id": "RQ-001",
  "evidence_id": "EV-001",
  "match_score": 0,
  "requirement_weight": 0,
  "credibility_level": "verified_document | user_stated | inferred | uncertain",
  "credibility_coefficient": 1.0,
  "composite_score": 0.0,
  "match_type": "direct | adjacent | transferable | none",
  "reason": "",
  "needs_review": false
}
```

## Review Guardrails

- Direct evidence must share the actual problem, method, deliverable, or responsibility,
  not merely a keyword.
- Adjacent evidence has low adaptation cost but must be described honestly.
- Transferable evidence demonstrates the underlying capability in another context.
- Lexical/script-generated scores are provisional and require human/agent review.
- Eligibility restrictions are pass/fail and must not be averaged away.
