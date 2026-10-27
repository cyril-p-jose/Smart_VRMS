# SETUP-05 — Apply the private identity foundation

## Purpose and prerequisites

SETUP-04 completion is user-reported; no result lines or independently verified database session were supplied to the agent. The next operation uses the Dashboard SQL Editor and does not need database credentials in chat or a locally saved password.

Apply only to the isolated **smart-vrms-dev** Free project, reference **wvtnepxynjjbazczkukh**, with no real business data. No installation, paid service, deployment or API exposure change is needed. Public signup and anonymous sign-in remain off.

Migration 001 creates a private `vrms_private` schema with company, staff membership, membership-audit and migration-history tables. It creates `smart_vrms_api` as **NOLOGIN** with no password, ownership, superuser, role-management or RLS-bypass permissions. It grants only company/membership reads, filtered by server-asserted identity and membership. It does not create an administrator, invitation, database login or business record, and does not make desktop sign-in work.

Every company must reference an active administrator membership in that same company. This deferred foreign key allows first-admin bootstrap or a handover in one transaction, and rejects deletion, deactivation or demotion of the designated administrator without a replacement. Application writes and audit access remain denied until separately reviewed operations are implemented. Editable Auth metadata is never used for a role.

Official references: [Supabase SQL tables](https://supabase.com/docs/guides/database/tables), [database roles](https://supabase.com/docs/guides/database/postgres/roles), [row-level security](https://supabase.com/docs/guides/database/postgres/row-level-security), [PostgreSQL policy semantics](https://www.postgresql.org/docs/current/sql-createpolicy.html).

## Exact steps

1. Open [Supabase Dashboard](https://supabase.com/dashboard), select **smart-vrms-dev**, and confirm project reference `wvtnepxynjjbazczkukh`. Open **SQL Editor** and create a new query. Use its default `postgres` database role.
2. Open Windows PowerShell. Run:

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
notepad '.\database\migrations\001_identity_foundation.sql'
```

3. Copy the **entire** file from Notepad into the new SQL query. It contains no secrets and needs no substitutions. Run it once. If Supabase warns about role/grant/revoke operations, these are the intentional access restrictions described above; inspect the query before confirming. Expected: success, usually “Success. No rows returned.” Do not run selected fragments.
4. Open a second, new SQL Editor query. In PowerShell run:

```powershell
notepad '.\database\verification\001_identity_foundation_checks.sql'
```

5. Copy that entire file into the second query and run it. It checks role attributes, grants, table ownership, RLS flags, the administrator constraint and zero rows under missing identity using `SET LOCAL ROLE smart_vrms_api`. Its temporary context changes are rolled back. Expected result contains:

```text
SETUP-05 foundation checks PASS; live staff isolation and runtime login remain pending
```

6. Under the project's **Data API settings**, inspect **Exposed schemas**. Confirm `vrms_private` is **not listed**. Do not add it or change existing schemas. The migration denies Data API role grants independently of this setting.
7. Send the safe reply below. After this gate, the next work is first-admin provisioning, restricted runtime credentials, authoritative membership lookup and live user-isolation checks. No complete security acceptance is claimed by these structural checks.

## Troubleshooting and recovery

- If the first attempt reported `ADMIN option cannot be granted back to your own grantor`, use the corrected local migration file. The redundant ADMIN grant has been replaced with explicit SET-role permission without inheritance. First run `ROLLBACK;` separately, then check that neither `vrms_private` nor `smart_vrms_api` exists using the recovery query in `database/verification/000_failed_migration_preflight.sql`. Both flags must be false before retrying the entire corrected migration. If either is true, stop and share those flags; do not drop existing objects.

- `VRMS objects already exist`: do not delete them or rerun migration 001. Run the verification file and share its sanitized result; the migration may already have succeeded.
- Any other migration error: stop. The file is wrapped in a transaction; do not manually run the remaining statements or add broad grants. Share the error text with passwords/connection strings removed. If the editor reports an aborted transaction, execute `ROLLBACK;` in that session before further checks.
- Verification error: do not proceed to bootstrap or enable a login. Share the error text; the guard intentionally blocks dependent work.
- Missing schema/table: confirm the project and that the whole migration succeeded.
- Permission error: confirm the SQL Editor role is `postgres`. Do not grant access to `anon` or `authenticated` to work around it.
- No visible result row: check the results from the SELECT before ROLLBACK in the verification batch. Do not interpret a generic “Success” as the requested PASS result; report what you see.

## Safe reply

```text
DONE SETUP-05
Migration 001: success / sanitized error
Foundation checks: PASS / sanitized error
vrms_private absent from Exposed schemas: yes/no
Any setup problem: none / description
```

## Evidence and limits

SQL has been reviewed locally against the approved private-schema design and official PostgreSQL/Supabase documentation. No local PostgreSQL executable or Docker was available, no tool was installed, and the SQL has not been executed by the agent. Live SQL execution is this explicit manual dependency. The existing Java build is unchanged by this SQL-only increment. Empty-table denial and catalog checks do not prove active/revoked user isolation, transaction-context cleanup, employee permissions, administrator handover concurrency, Storage rules or application login; those remain pending populated integration tests. No first admin or legal company identity is invented.
