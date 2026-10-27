# Smart VRMS business prototype — current status

## Published candidate

`0.2.1-preview` is the current Windows beta candidate. Its downloadable package is built from the merged `main` source. The user-confirmed live acceptance is staff sign-in and reading company fleet rows. Migration 009 and its six expected indexes were also confirmed in the Supabase development project. These checks do not prove that all live business workflows pass.

## Source and local verification

The merged source contains the Swing desktop, Java API, Supabase Auth/PostgreSQL adapters, protected staff/company context, and the implemented rental workflow modules. The local fixture/regression suite passed 1,654 assertions in the documented run. The public API health endpoint returned HTTP 200 in a read-only check. Local fixture checks and API liveness are not substitutes for authenticated live workflow tests.

The signed-out **Explore interface preview** now uses one coherent fictional dataset across fleet, customers, drivers, personnel, running rentals, finance, reviews, and reports. It contains 10 examples per area, masked/non-valid identifiers, and sample totals that reconcile. No example is sent to the API or seeded into the company database; email delivery remains un-simulated. See [Demo Data Catalog](Demo-Data-Catalog.md).

## Limits to keep visible

- No end-to-end authenticated create/modify/return/payment journey has been operator-accepted against the hosted database after the merged release source.
- Users need an already invited staff account and active company membership. A downloaded app does not create accounts or grant company access.
- Payments are manually recorded (including UPI); there is no automatic gateway receipt confirmation.
- Outbound email/reminder delivery and backup/restore drills have not been confirmed operational.
- The app is a beta preview, unsigned on Windows, and macOS/Linux packages are not supplied.
- The free host may sleep or be unavailable. Do not promise uptime or use this build as a production accounting/payroll system.

## Evidence references

- [Release audit](Release-Audit.md)
- [Operator and deployment guide](Operator-and-Deployment-Guide.md)
- [90-item requirements tracker](../planning/Requirements-and-Verification.csv)
- [Migration 009 index audit](../database/verification/005_migration_009_index_audit.sql)
