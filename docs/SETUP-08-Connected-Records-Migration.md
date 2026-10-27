# SETUP-08 — Apply Connected Records Migration (Stage 3)

## Purpose and prerequisites

Migration 004 deploys the relational schema for Stage 3 (Connected records and personnel):
- Personnel: `employees`, `attendance_records`, `compensation_records` (FR-EMP-001 through FR-EMP-008).
- Fleet: `vehicle_categories`, `vehicles`, `vehicle_documents`, `asset_restrictions` (FR-VEH-001, FR-VEH-002, FR-VEH-004, FR-VEH-006, FR-DOC-001, FR-DOC-002).
- Drivers: `driver_profiles` with operational projections strictly isolated from compensation and margin data (FR-DRV-001, FR-DRV-003, FR-ACC-003).
- Customers: `customers` and `customer_visits` with age-at-visit context (`[18, 120]`) and mandatory booking context (FR-CUST-001, FR-CUST-002, FR-CUST-008).

Prerequisites:
- Isolated development project: **smart-vrms-dev**, ref `wvtnepxynjjbazczkukh`.
- Migration 003 (`003_shared_security_files_audit.sql`) has been applied (version = 2 recorded in `vrms_private.schema_migrations`).
- Run as `postgres` in Supabase SQL Editor.
- Zero secrets or credentials in chat or git.

Official references: [PostgreSQL Row Level Security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html), [Supabase SQL Editor](https://supabase.com/docs/guides/database/tables).

## Exact steps

1. Open the [Supabase Dashboard](https://supabase.com/dashboard), navigate to **smart-vrms-dev** (`wvtnepxynjjbazczkukh`), and select **SQL Editor**.
2. Open a new query tab.
3. Open Windows PowerShell:
```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
notepad '.\database\migrations\004_connected_records.sql'
```
4. Copy the entire contents of `database/migrations/004_connected_records.sql` into the SQL Editor query window.
5. Execute the query.
6. Expected output:
```text
Success. No rows returned.
```
7. Verify that version 3 is recorded by executing:
```sql
SELECT version, applied_at FROM vrms_private.schema_migrations ORDER BY version;
```
Expected output:
```text
version | applied_at
1       | ...
2       | ...
3       | <current timestamp>
```

## Troubleshooting and recovery

- **Error: `Migration 003 must be applied before migration 004.`**:
  - Run `003_shared_security_files_audit.sql` first before running migration 004.
- **Error: `Migration 004 is already recorded as applied.`**:
  - The migration was already executed. Do not rerun it.
- **Transaction lock timeout**:
  - The script sets a 5s lock timeout and 30s statement timeout. If concurrent transactions are holding table locks, wait a moment and re-run.

## Safe reply template

```text
DONE SETUP-08
Migration 004: success / sanitized error
Schema migrations version: 3 confirmed
Any setup problem: none / description
```
