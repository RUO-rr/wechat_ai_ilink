# Resume Template Design Families

## Contents

1. Selection rules
2. ATS Clean
3. Research Impact
4. Modern Sidebar
5. Academic CV
6. Shared constraints
7. Line-break rules
8. Paragraph alignment rules
9. Editable tuning version
10. Visual references

## Selection Rules

Choose information architecture before color. Use the target, delivery channel, amount
of evidence, and likely reader to select a family.

| Family | Best for | Default length | ATS safety | Visual emphasis |
|---|---|---:|---|---|
| ATS Clean | Online industry applications, unknown ATS | 1-2 pages | Highest | Hierarchy and evidence |
| Research Impact | Scientist, R&D, biotech, technical research roles | 1-2 pages | High | Projects, methods, outcomes |
| Modern Sidebar | Networking, direct email, portfolio-led applications | 1 page | Medium | Identity and skill scanning |
| Academic CV | Faculty, postdoc, grant, academic review | Multi-page | Not the main concern | Outputs and chronology |

Default to `ATS Clean` unless another family is clearly better. Never choose a sidebar
only to make the document appear more designed.

## ATS Clean

Use a strict single reading column.

Characteristics:

- name and contact line at the top;
- standard section headings such as `Education`, `Experience`, `Projects`,
  `Publications`, and `Skills`;
- black/charcoal text with one restrained accent;
- thin rules and generous alignment instead of boxes;
- right-aligned dates that remain in DOM reading order;
- no portrait, icon-only labels, charts, skill bars, or decorative side panels.
- no tables for core resume content unless a separate HTML/PDF visual version is also
  requested;
- no icons, text boxes, multi-column layouts, or image-only text.

Best for application portals and roles where parsing reliability matters. The HTML
version is an ATS-oriented visual resume, but a parser-first ATS request must also
receive a plain Markdown or DOCX-friendly version that does not use CSS grid, tables,
icons, text boxes, multi-column layout, or image-only text for core content.

## Research Impact

Use a mostly single-column structure optimized for research evidence.

Characteristics:

- concise research identity or qualification summary;
- optional compact "Methods and Domains" line near the top;
- project entries organized as problem, personal contribution, methods, and outcome;
- selected publications, patents, protocols, datasets, or software as a short section;
- restrained scientific color such as teal, deep green, or burgundy;
- slightly stronger section hierarchy than ATS Clean without adding decoration.

Use for industry scientist, research associate, computational biology, chemistry,
engineering R&D, and laboratory roles. Keep methods attached to evidence rather than
turning the resume into a keyword inventory.

## Modern Sidebar

Use a narrow sidebar and a dominant main column.

Characteristics:

- sidebar occupies no more than 28-30% of page width;
- sidebar contains only short items: contact, links, core tools, languages;
- main column contains all chronology and achievement bullets;
- no circular skill meters or infographic charts;
- use a light or dark neutral sidebar with high contrast;
- ensure mobile and copy/paste reading order remains coherent.

Use only for direct human review, networking, portfolio contexts, or when the user
explicitly prefers it. Generate an ATS Clean variant as well when the same application
also goes through a portal.

## Academic CV

Use a publication-forward, multi-page chronological document.

Characteristics:

- classic typography and minimal color;
- education, appointments, research interests, publications, grants, teaching,
  supervision, service, awards, and talks as independent sections;
- hanging indents or numbered lists for citations;
- repeated page header/footer permitted;
- page breaks controlled at section and entry boundaries;
- complete output lists can use bibliographic formatting.

Do not compress an academic CV into a one-page resume or inflate an industry resume
into a CV. Ask which document the institution requests.

## Shared Constraints

- Design for A4 and Letter without relying on browser default margins.
- Use system fonts and live text.
- Keep body text at a readable print size.
- Avoid remote assets, gradients, shadows, and decorative backgrounds.
- Maintain grayscale contrast and semantic heading order.
- Use at most one main accent plus neutral colors.
- Do not put essential information in icons, pseudo-elements, or images.
- In ATS versions, avoid tables, text boxes, multi-column layouts, icons, and
  image-only text for core resume content.
- Every visual family must pass the same factual and structural checks.

## Line-Break Rules

Do not allow short semantic fields to break inside the field. This is especially
important for education lines such as school, college, degree, and major.

Bad:

`华中师范大学 | 化学学院 | 理学硕士 | 物理化`
`学`

Good:

`华中师范大学 | 化学学院 | 理学硕士`
`物理化学`

Rules:

- Wrap names, school names, college names, degree names, majors, cities, company names,
  journal names, project names, method names, chemical/protein names, and short
  technical terms in `<span class="keep-token">...</span>` or an equivalent no-break
  class.
- Allow line breaks only between fields or after separators, not inside tokens such as
  `物理化学`, `LC-MS/MS`, `chemical proteomics`, `蛋白质组学`, `click chemistry`,
  `Python`, `理学硕士`, or institution names.
- Use CSS `word-break: keep-all`, `overflow-wrap: normal`, and `white-space: nowrap`
  on short token spans.
- Keep dates in a no-break element.
- Avoid isolated punctuation at the beginning of a line and single orphan characters at
  the end of a line in Chinese resumes.
- If the row is too crowded, move an entire token group to the next line, reduce
  separator spacing, shorten less important detail, or use a two-line education layout.
  Do not solve crowding by allowing mid-token breaks.

## Paragraph Alignment Rules

For the main content area, use controlled justification:

- Apply `text-align: justify` to summaries, bullet items, and skill descriptions when
  the resume contains dense Chinese/English mixed content.
- Keep `text-align-last: left` so the last line of a bullet does not stretch awkwardly.
- Do not insert manual line breaks inside bullets unless they represent semantic
  separation. Let the browser wrap text.
- Use CSS for spacing and alignment instead of hard-coded `<br>`.
- Do not use `word-break: break-all`; it can split Chinese majors and English method
  names.
- Preserve technical tokens with `keep-token` spans when needed, especially names such
  as `HRMS/LC-MS`, `LC-MS/MS`, `Zeta potential`, `pChem`, and `DIA-NN`.
- If justification creates large spaces around English terms, keep the same content
  but switch the editable CSS variable `--content-align` from `justify` to `left`, or
  shorten the bullet.

## Editable Tuning Version

When the user wants to self-edit or fine tune layout, provide an editable HTML copy
based on `assets/templates/resume_editable_tuning.html`. Tell the user to adjust the
variables near the top of the file:

- `--body-size`
- `--small-size`
- `--line-height`
- `--page-padding-x`
- `--page-padding-y`
- `--content-align`
- `--list-indent`

This editable version is the preferred hand-tuning artifact. Do not ask the user to
edit generated PDF directly.

## Visual References

Use these sources for comparison and inspiration only. Create original HTML and CSS;
do not copy proprietary templates.

- [Overleaf CV and resume gallery](https://www.overleaf.com/latex/templates/tagged/cv):
  useful for comparing publication-heavy, sidebar, and classic multi-page structures.
- [AltaCV on Overleaf](https://www.overleaf.com/latex/templates/altacv-template/trgqjpwnmtgv):
  a strong reference for dense two-column hierarchy and optional publications.
- [Simple Hipster CV on Overleaf](https://www.overleaf.com/latex/templates/simple-hipster-cv/cnpkkjdkyhhw):
  a modern sidebar reference; use cautiously for ATS delivery.
- [ModernCV and Cover Letter on Overleaf](https://www.overleaf.com/latex/templates/moderncv-and-cover-letter-template/sttkgjcysttn):
  a restrained multi-page professional/academic reference.
- [JSON Resume themes](https://jsonresume.org/themes):
  open-source HTML theme gallery showing how one structured profile can support
  multiple visual outputs.
- [JSON Resume Academic CV Lite](https://registry.jsonresume.org/thomasdavis?theme=academic-cv-lite):
  a compact academic structure reference.
- [JSON Resume Nordic Minimal](https://registry.jsonresume.org/thomasdavis?theme=nordic-minimal):
  a minimal single-column visual reference.
- [JSON Resume Sidebar](https://registry.jsonresume.org/thomasdavis?theme=sidebar):
  a sidebar layout reference.
