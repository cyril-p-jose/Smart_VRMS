# SETUP-12 — Apply Migration 008 (Transactional Outbox, Reminders, Reviews & Reporting)

## 1. Purpose
Apply additive database migration `database/migrations/008_automation_outbox_and_reporting.sql` to the authoritative PostgreSQL database in Supabase. This establishes the transactional outbox (`outbox_messages`), durable delivery attempt logs (`delivery_attempts`), document expiry deduplication scans (`document_reminder_scans`), voluntary post-settlement customer reviews (`service_reviews`), and versioned feedback corrections (`service_review_corrections`) (FR-DOC-003, FR-DOC-004, FR-BOOK-011, FR-MSG-001, FR-MSG-002, FR-REV-001, FR-REV-002, FR-FIN-003, FR-FIN-005, FR-ACC-003).

## 2. Prerequisites & Cost
- **Prerequisites**: SETUP-11 completed (schema version 6 present in `vrms_private.schema_migrations`).
- **Cost**: ₹0 (Free tier Supabase project).
- **Official Documentation**: [Supabase SQL Editor](https://supabase.com/docs/guides/database/overview).

## 3. Official Links
- Supabase Dashboard: `https://supabase.com/dashboard/project/wvtnepxynjjbazczkukh`
- SQL Editor: `https://supabase.com/dashboard/project/wvtnepxynjjbazczkukh/sql`

## 4. Numbered Action Steps
1. Open the browser and navigate to the Supabase SQL Editor:
   `https://supabase.com/dashboard/project/wvtnepxynjjbazczkukh/sql/new`
2. Open the file `database/migrations/008_automation_outbox_and_reporting.sql` in `C:\Users\HP\Desktop\smart-VRMS`.
3. Copy the entire contents of `database/migrations/008_automation_outbox_and_reporting.sql`.
4. Paste into a new query window in the Supabase SQL Editor.
5. Click **Run** (or press `Ctrl+Enter` / `Cmd+Enter`).
6. Confirm the script executes successfully with status `Success. No rows returned`.
7. Execute the following verification query in a new query tab:
   ```sql
   SELECT version, description, applied_at
   FROM vrms_private.schema_migrations
   ORDER BY version;
   ```
8. Confirm version `7` is present:
   - `version`: `7`
   - `description`: `'automation outbox, reminders, reviews, and reporting'`

## 5. Expected Output
Verification query returns 7 rows with `version` values `1`, `2`, `3`, `4`, `5`, `6`, and `7`.

## 6. Troubleshooting & Recovery
- **Error `relation "vrms_private.billing_documents" does not exist`**:
  - Cause: Migration 007 was not applied.
  - Recovery: Apply `007_finance_ledger_and_documents.sql` (SETUP-11) before applying migration 008.
- **Error `duplicate key value violates unique constraint "schema_migrations_pkey"`**:
  - Cause: Migration 008 was already applied.
  - Recovery: Check `SELECT * FROM vrms_private.schema_migrations;`. If version 7 is present, do not re-run.

## 7. Secret-Free DONE Template
Respond with:
```
SETUP-12 done and success. Schema version 7 applied.
```
