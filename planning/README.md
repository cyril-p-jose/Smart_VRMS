# Smart VRMS planning package

Prepared: 25 October 2026; research checked 24–25 October 2026. Status: **DRAFT FOR REVIEW — implementation is not authorized.**

Start with [the consolidated specification](Smart-VRMS-Planning-Baseline.md). The [requirement and verification register](Requirements-and-Verification.csv) gives each mandatory requirement a stable identifier, feature contract, acceptance criterion, implementation stage, and evidence status. [Representative layouts](Representative-Layouts.svg) are conceptual artwork for design review, not screenshots or implemented functionality.

For a normal image viewer, use the [rendered layout preview](Representative-Layouts.png).

## Review decisions

The accepted user answers are preserved in specification section 3. Review these new recommendations before approving the baseline:

1. Pricing: first 24-hour minimum; subsequent full days plus hourly remainder at daily rate/24; proposed 30-minute grace for late return; decimal line rounding to paise; no duplicate extension charge. See section 4 and examples in section 8.
2. Tax/age: verify actual GST status before real tax invoicing; capture age with visit/as-of date rather than requiring DOB.
3. Permissions/closure: employees finalize routine standard invoices but cannot discount/refund/correct finance; acceptable unpaid return releases assets and retains open debt; final closure follows settlement.
4. Architecture: Java API with restricted server JDBC transactions and private schema; Supabase Auth/Storage over HTTPS; four Maven modules; restricted hosted Java worker. These are proposed boundaries, not implemented modules.
5. UI and dates: native Swing patterns and provided layouts; DD/MM/YY compact display with full-year accessible detail, full-year editing/PDFs; native title bars and logical system fonts.
6. Reporting/service level: operating accrual margin plus separate cash/deposit views; Monday weeks and April–March financial year; free-beta quotas/cold starts and best-effort scheduled delivery remain visible.
7. Distribution: proposed Windows11 x64, macOS15+ separately tested Intel/Apple Silicon, Ubuntu24.04 GNOME x64; native device checks and signing prerequisites before public release.

G0 planning approval does not approve unnamed provider accounts, billing, installing software, unreviewed dependencies, deployment or publication. Dependency candidates have specific versions but exact artifact/transitive size and advisory checks remain a pre-addition gate. Real GST facts, sender/domain, bank merchant setup and signing access require manual prerequisites only when dependent work is due.

Suggested reply after review: `Approve planning baseline v0.4 for Stage 0 readiness; changes: ...` or `Revise these items: ...`. The explicit planning gate is required by the user's foundational and development/release instructions.

## Document checks

90 individually identified requirement records; 17 consistent CSV columns; no duplicate requirement IDs; all delivery statuses Planned. SVG XML parsed and its PNG preview was rendered using an already bundled tool and visually inspected. This verifies document structure/artwork only. No application build, database integration, email delivery, performance, installer, or device acceptance check has run.

The user's foundational contract and development/release procedure remain controlling instructions. Accepted answers are distinguished from recommendations and unresolved prerequisites. An approved draft must be explicitly versioned as the implementation baseline before application code, executable migrations, infrastructure, or deployments begin.

All features in the original discovery prompt remain mandatory. Free beta service limitations and public release prerequisites are documented rather than hidden by scope reductions. No dependency, provider, paid plan, source visibility change, or publication is approved by the existence of these documents.

The CSV is UTF-8. Each row defines purpose, actor/permission, inputs/outputs, normal and exception paths, conceptual data links, cross-module effects, notifications, measurable acceptance criteria, target stage/components and evidence status. All delivery statuses are Planned and all execution evidence is Not run; this is not a claim that application testing has occurred. Requirement field lists are contractual even where grouped within a row.
