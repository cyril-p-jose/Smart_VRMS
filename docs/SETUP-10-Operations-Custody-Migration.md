# SETUP-10 — Apply Operations, Custody, Extensions & Maintenance Migration (Stage 5)

## Purpose and prerequisites

Migration 006 deploys the relational schema for Stage 5 (Running services, custody checkout/return, extensions at saved rates, and maintenance safety gates):
- Custody records: `vrms_private.custody_records` with assignment linkage, vehicle ID, checkout and return timestamps, starting/ending odometer and fuel percentage, inspection status (`PENDING`, `ACCEPTABLE`, `DAMAGED`), notes, and inspection photo file linkage (FR-OPS-002, FR-OPS-003).
- Extension records: `vrms_private.extension_records` with booking ID, assignment ID, previous end time, revised end time, incremental charge paise at original snapshot rates, reason, and actor linkage (FR-EXT-001, FR-EXT-002, FR-EXT-003).
- Maintenance cases: `vrms_private.maintenance_cases` with vehicle ID, optional assignment and custody linkage, linked asset restriction, issue type (`CUSTOMER_DAMAGE`, `ORDINARY_WEAR`, `ROUTINE_SERVICE`, `BREAKDOWN`), case status (`OPEN`, `ASSESSED`, `REPAIR_IN_PROGRESS`, `RESOLVED`), estimated vs actual cost, alleged vs approved recovery, description notes, resolution notes, and reporting/resolving actor tracking (FR-DMG-001, FR-DMG-002, FR-DMG-003, FR-DMG-004).
- Row-Level Security: Mandatory tenant isolation policies across all tables with grants to restricted `smart_vrms_api` role.

Prerequisites:
- Isolated development project: **smart-vrms-dev**, ref `wvtnepxynjjbazczkukh`.
- Migration 005 (`005_booking_engine.sql`) has been applied (version = 4 recorded in `vrms_private.schema_migrations`).
- Run as `postgres` in Supabase SQL Editor.
- Zero secrets or credentials in chat or git.

Official references: [PostgreSQL Constraints & Checks](https://www.postgresql.org/docs/current/ddl-constraints.html), [PostgreSQL Row Level Security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html).

## Exact steps

1. Open the [Supabase Dashboard](https://supabase.com/dashboard), navigate to **smart-vrms-dev** (`wvtnepxynjjbazczkukh`), and select **SQL Editor**.
2. Open a new query tab.
3. Open Windows PowerShell:
```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
notepad '.\database\migrations\006_operations_and_extensions.sql'
```
4. Copy the entire contents of `database/migrations/006_operations_and_extensions.sql` into the SQL Editor query window.
5. Execute the query.
6. Expected output:
```text
Success. No rows returned.
```
7. Verify that version 5 is recorded by executing:
```sql
SELECT version, description, applied_at FROM vrms_private.schema_migrations ORDER BY version;
```
Expected output:
```text
version | description                                  | applied_at
1       | initial identity foundation                   | ...
2       | shared security, files, audit                | ...
3       | connected records and personnel              | ...
4       | booking engine, packages, reservations, rates | ...
5       | operations, custody, extensions, maintenance | <current timestamp>
```

## Troubleshooting and recovery

- **Error: `Migration 005 must be applied before migration 006.`**:
  - Run `005_booking_engine.sql` first before running migration 006.
- **Error: `Migration 006 is already recorded as applied.`**:
  - The migration was already executed. Do not rerun it.
- **Transaction lock timeout**:
  - The script sets a 5s lock timeout and 30s statement timeout. If concurrent transactions are holding locks, wait a moment and re-run.

## Safe reply template

```text
DONE SETUP-10
Migration 006: success / sanitized error
Schema migrations version: 5 confirmed
Any setup problem: none / description
```
