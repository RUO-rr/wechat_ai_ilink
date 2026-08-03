# Job Requirement Extraction Schema

Split job intelligence into atomic requirements. Do not combine multiple independently
scorable requirements in one record.

## Schema

```json
{
  "requirement_id": "RQ-001",
  "requirement_text": "",
  "category": "responsibility | function | daily_task | skill | domain | tool | platform | method | standard | output | qualification | major | experience_years | language | certificate | restriction | bonus | inferred",
  "requirement_type": "explicit | tentative | market_general",
  "importance": "core | required | preferred | auxiliary",
  "requirement_weight": 1,
  "source_id": "JS-001",
  "source_credibility": "A | B | C | D | E | F",
  "access_date": "YYYY-MM-DD",
  "evidence_excerpt": "",
  "notes": ""
}
```

## Required Extraction Coverage

Use stable job requirement IDs: `RQ-001`, `RQ-002`, ...

Extract:

1. principal responsibilities;
2. core functions;
3. daily tasks;
4. expected outputs/deliverables;
5. domain/professional knowledge;
6. skills;
7. tools, instruments, software, and platforms;
8. standards, regulations, methods, and workflows;
9. degree and major;
10. years and type of experience;
11. language;
12. certificates/qualifications;
13. restrictions and logistical screens;
14. preferred/bonus items;
15. implicit capabilities.

## Requirement Types

- `explicit`: supported by A, B, or C job sources.
- `tentative`: supported by D or E job sources and marked with source type.
- `market_general`: common in the occupation but not supported for this exact role.

Never rewrite tentative or market-general material as explicit. A `market_general`
item may guide questions but must not dominate resume selection.

## Importance

- `core`: defines the role's principal purpose or recurring responsibility.
- `required`: a stated screening qualification or essential skill.
- `preferred`: an advantage or bonus item.
- `auxiliary`: context, generic behavior, or low-priority support.

If one sentence contains degree, years, and tool requirements, create separate records
linked to the same excerpt.

## Requirement Weight

Assign `requirement_weight` from `1` to `5` for matching:

- `5`: mandatory / eligibility-critical.
- `4`: strongly preferred.
- `3`: relevant but not central.
- `2`: weakly relevant.
- `1`: background signal.

When unsure, keep the weight conservative and explain the uncertainty in `notes`.
