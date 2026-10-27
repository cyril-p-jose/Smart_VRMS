# SETUP-13 — Live schema and restricted-role evidence

Purpose: establish the actual applied schema before enabling business services. The prior source audit found a missing context function and disconnected workflows; current local finance, automation, review, and reporting adapters now use `JdbcStaffContext` in explicit transactions. Fixture tests cannot confirm the later migrations or live behavior. This gate does not deploy anything or make a business release ready.

Prerequisites: the existing isolated development project, SQL Editor operator access, and your privately retained restricted server-role password. No new tools, accounts, subscription or fee are required. Do not send passwords or connection strings.

Official references: [Supabase database tables and SQL](https://supabase.com/docs/guides/database/tables), [roles](https://supabase.com/docs/guides/database/postgres/roles), [PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html).

1. In Supabase Dashboard, confirm project reference wvtnepxynjjbazczkukh. Open a new SQL Editor query as postgres.
2. In Windows PowerShell run:

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
notepad '.\database\verification\003_release_schema_audit.sql'
```

3. Copy the entire SQL file into that new query and run it. It is read-only and returns migration versions, a context-function existence flag, table RLS flags, role attributes and grants. It returns no business records. Inspect each result set using the editor's result selector. Expected output is metadata rows; a false function-existence flag is expected by current local source and is not a request to create the function manually.
4. Run the existing restricted-role check in a normal terminal:

```powershell
.\scripts\setup-runtime-role.ps1 -CheckOnly
```

Enter only the retained server-role password at the hidden prompt. This mode never resets a password or changes grants. Expected: restricted login/TLS/context checks PASS. If the check fails, share only its sanitized message; do not rerun provisioning automatically.
5. Send the migration-version list, function-existence flag and role-check result. Do not apply or rerun migrations to work around a reported gap. The next repair must use the real applied state and an additive reviewed migration where necessary.

Safe reply:

```text
DONE SETUP-13
Applied migration versions: ...
Legacy context function exists: true/false
All application tables show RLS enabled/forced: yes/no
Runtime role superuser/BYPASSRLS/CREATEROLE/CREATEDB/replication all false: yes/no
Restricted role check: PASS / sanitized message
```

Troubleshooting: if a query errors, stop and share the error text without secrets. Missing private schema means the wrong project or missing setup. If PowerShell scripts are blocked, report the policy error without changing machine policy. The user confirmed migration version 8, all six expected migration 009 indexes, and current-source staff authentication/restricted role/TLS/in-transaction fleet read. Do not rerun migration 009; continue real user-role/concurrency/storage/payment acceptance.
