# Smart VRMS release audit — 0.2.1-preview

Date: 27 October 2026 (Asia/Kolkata). Classification: **beta preview**. Production readiness is **not established**. This audit records evidence rather than treating code presence or local test success as proof that a complete rental business can safely operate the system.

## Verified evidence

| Check | Result | Boundary |
|---|---|---|
| Merged source | GitHub `main` contains the reviewed implementation | Source merge is not deployment acceptance for every route |
| Local verification | Repository verification suite previously passed 1,654 assertions | Fixture/regression coverage does not prove hosted database behavior |
| Supabase schema | Operator confirmed migration 009 and all six expected indexes | Catalog/schema check only |
| Staff authentication | Operator reported current-source live check PASS | Auth and session validation only |
| Fleet read | Operator reported packaged staff sign-in PASS and fleet rows loaded | Live read path only; no authenticated write accepted |
| Public API liveness | Read-only `/health` request returned HTTP 200 | Health is not a business workflow |
| Windows package | `jpackage` Windows x64 app image and ZIP built locally | Unsigned; needs release CI to reproduce and publish |
| Demo data | 10 fictional records per preview domain, with masked contacts and `DEMO-` identifiers | Local, read-only presentation data; not stored in Supabase |

## Not accepted as a complete business workflow

The full authorized Admin/Employee journeys still need live acceptance on the hosted development project: create/edit records, booking conflict and concurrency behavior, checkout/return and inspection, extensions, payment allocation/reconciliation/refund/correction, document upload/access denial, reminder delivery, and company-boundary denial. Use approved fictional records for that work. The user has not supplied a result for those journeys, so this audit does not mark them PASS.

The prototype records payment manually; no automatic payment-gateway callback is enabled. Outbound email/reminder delivery, statutory payroll, backup/restore, and recovery objectives are not claimed as complete. Unsigned Windows distribution and absence of macOS/Linux artifacts are known limits.

## Release rule

Only publish this package as `0.2.1-preview`. Do not call it bug-free, production-ready, or a complete business system. Do not use it as the sole source of financial/accounting records until the live workflows and recovery controls have been accepted. A later stable release requires live workflow and authorization tests, backup/restore evidence, mail-worker operations if reminders are promised, and signed platform packages for every advertised OS.

See [Business Prototype Progress](Business-Prototype-Progress.md), [Operator Guide](Operator-and-Deployment-Guide.md), and the [requirements tracker](../planning/Requirements-and-Verification.csv).
