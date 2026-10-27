# SETUP-11 — Apply Migration 007 (Finance, Payments, Ledger & Documents)

## 1. Purpose
Apply additive database migration `database/migrations/007_finance_ledger_and_documents.sql` to the authoritative PostgreSQL database in Supabase. This establishes the immutable double-entry financial ledger, verified customer payments, itemized payment allocations, separate refundable security deposits, administrator financial corrections, and immutable billing document snapshots (FR-PAY-001..004, FR-FIN-001..005, FR-PDF-001..002).

## 2. Prerequisites & Cost
- **Prerequisites**: SETUP-10 completed (schema version 5 present in `vrms_private.schema_migrations`).
- **Cost**: ₹0 (Free tier Supabase project).
- **Official Documentation**: [Supabase SQL Editor](https://supabase.com/docs/guides/database/overview).

## 3. Official Links
- Supabase Dashboard: `https://supabase.com/dashboard/project/wvtnepxynjjbazczkukh`
- SQL Editor: `https://supabase.com/dashboard/project/wvtnepxynjjbazczkukh/sql`

## 4. Numbered Action Steps
1. Open the browser and navigate to the Supabase SQL Editor:
   `https://supabase.com/dashboard/project/wvtnepxynjjbazczkukh/sql/new`
2. Open the file `database/migrations/007_finance_ledger_and_documents.sql` in `C:\Users\HP\Desktop\smart-VRMS`.
3. Copy the entire contents of `database/migrations/007_finance_ledger_and_documents.sql`.
4. Paste into a new query window in the Supabase SQL Editor.
5. Click **Run** (or press `Ctrl+Enter` / `Cmd+Enter`).
6. Confirm the script executes successfully with status `Success. No rows returned`.
7. Execute the following verification query in a new query tab:
   ```sql
   SELECT version, description, applied_at
   FROM vrms_private.schema_migrations
   ORDER BY version;
   ```
8. Confirm version `6` is present:
   - `version`: `6`
   - `description`: `'finance, payments, ledger, deposits, and billing documents'`

## 5. Expected Output
Verification query returns 6 rows with `version` values `1`, `2`, `3`, `4`, `5`, and `6`.

## 6. Troubleshooting & Recovery
- **Error `relation "vrms_private.custody_records" does not exist`**:
  - Cause: Migration 006 was not applied.
  - Recovery: Apply `006_operations_and_extensions.sql` (SETUP-10) before applying migration 007.
- **Error `duplicate key value violates unique constraint "schema_migrations_pkey"`**:
  - Cause: Migration 007 was already applied.
  - Recovery: Check `SELECT * FROM vrms_private.schema_migrations;`. If version 6 is present, do not re-run.

## 7. Secret-Free DONE Template
Respond with:
```
SETUP-11 done and success. Schema version 6 applied.
```
