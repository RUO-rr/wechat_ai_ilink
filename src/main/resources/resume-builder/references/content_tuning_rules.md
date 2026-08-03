# Content Tuning Rules

Use this when the user wants room to make small wording adjustments after a resume is
drafted.

## Purpose

Provide an editable content layer between the evidence-backed draft and the final
HTML/PDF. The user should be able to adjust emphasis, length, tone, and terminology
without breaking traceability.

## What to Provide

Create `content-tuning-sheet.md` from
`assets/templates/content_tuning_sheet.md`.

For each important section or bullet, include:

- section name;
- current final wording;
- short version;
- standard version;
- expanded version;
- target requirement IDs;
- candidate evidence IDs;
- required keywords/technical terms;
- optional removable phrase;
- tone setting;
- user-editable notes;
- risk or confirmation flag.

## Allowed Micro-Adjustments

- Shorten a bullet while keeping the same claim.
- Expand a bullet with already verified method, tool, output, or impact.
- Move emphasis between problem, method, contribution, and result.
- Replace vague words with precise terms from evidence.
- Reduce keyword density when wording feels unnatural.
- Switch tone between conservative, standard, and stronger wording.
- Mark content as keep, compress, move, or delete.

## Not Allowed

- Add unverified metrics, papers, patents, awards, tools, or responsibilities.
- Upgrade `参与` into `主导` without evidence.
- Convert adjacent or transferable evidence into direct experience.
- Change publication or patent status.
- Add unsupported job keywords only to satisfy ATS.
- Remove evidence links from final bullets.

## Tone Levels

- `conservative`: safest wording for uncertain or collaborative work.
- `standard`: default resume wording.
- `strong`: only when ownership and result are well supported.

## Length Levels

- `short`: one compact phrase or sentence, useful for dense one-page resumes.
- `standard`: one clear bullet with action, method, and result.
- `expanded`: more context for research CVs or when the item is a lead experience.

## Delivery Rule

When the user asks to fine tune content, deliver both:

1. the current resume file;
2. a content tuning sheet with editable alternatives.

After the user edits the sheet, update the HTML/Markdown resume from the edited
standard/final column and rerun quality checks.
