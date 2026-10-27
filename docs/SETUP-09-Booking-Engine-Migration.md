# SETUP-09 — Apply Booking Engine & Rate Plans Migration (Stage 4)

## Purpose and prerequisites

Migration 005 deploys the relational schema for Stage 4 (Pricing and atomic single/package bookings):
- Master rate plans: `rate_plans` with vehicle and category daily/hourly rates and driver customer charges (FR-BOOK-007, FR-DATA-001).
- Atomic bookings: `bookings` with itemized financial breakdown, agreed advance vs. actual received separation, and pricing snapshots (FR-BOOK-001, FR-BOOK-005, FR-BOOK-007, FR-BOOK-008, FR-BOOK-010).
- Single and multi-vehicle packages: `booking_assignments` with individual rate versioning and driver assignments (FR-BOOK-002, FR-BOOK-003).
- Concurrent resource allocation: `vehicle_reservations` and `driver_reservations` tracking active booking windows and enforcing mutual exclusion (FR-BOOK-010, FR-VEH-005, FR-DRV-002).

Prerequisites:
- Isolated development project: **smart-vrms-dev**, ref `wvtnepxynjjbazczkukh`.
- Migration 004 (`004_connected_records.sql`) has been applied (version = 3 recorded in `vrms_private.schema_migrations`).
- Run as `postgres` in Supabase SQL Editor.
- Zero secrets or credentials in chat or git.

Official references: [PostgreSQL Constraints & Ranges](https://www.postgresql.org/docs/current/rangetypes.html), [PostgreSQL Row Level Security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html).

## Exact steps

1. Open the [Supabase Dashboard](https://supabase.com/dashboard), navigate to **smart-vrms-dev** (`wvtnepxynjjbazczkukh`), and select **SQL Editor**.
2. Open a new query tab.
3. Open Windows PowerShell:
```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
notepad '.\database\migrations\005_booking_engine.sql'
```
4. Copy the entire contents of `database/migrations/005_booking_engine.sql` into the SQL Editor query window.
5. Execute the query.
6. Expected output:
```text
Success. No rows returned.
```
7. Verify that version 4 is recorded by executing:
```sql
SELECT version, description, applied_at FROM vrms_private.schema_migrations ORDER BY version;
```
Expected output:
```text
version | description                                  | applied_at
1       | initial identity foundation                   | ...
2       | shared security, files, audit                | ...
3       | connected records and personnel              | ...
4       | booking engine, packages, reservations, rates | <current timestamp>
```

## Troubleshooting and recovery

- **Error: `Migration 004 must be applied before migration 005.`**:
  - Run `004_connected_records.sql` first before running migration 005.
- **Error: `Migration 005 is already recorded as applied.`**:
  - The migration was already executed. Do not rerun it.
- **Transaction lock timeout**:
  - The script sets a 5s lock timeout and 30s statement timeout. If concurrent transactions are holding locks, wait a moment and re-run.

## Safe reply template

```text
DONE SETUP-09
Migration 005: success / sanitized error
Schema migrations version: 4 confirmed
Any setup problem: none / description
```
