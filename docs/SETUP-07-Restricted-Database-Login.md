# SETUP-07 — Restricted server database login

Purpose: enable the existing smart_vrms_api role and verify a real login using that role rather than the schema owner. Prerequisites: completed SETUP-06, active development project, existing CA certificate, database administrator password, and a new unique server-role password of at least 20 characters. No software installation, paid service or deployment is required.

The new password is a server credential, distinct from your database administrator password and staff sign-in password. Retain it in your password manager. The script prompts privately and neither displays nor saves passwords. They temporarily exist in process memory. Do not send them to Codex or Antigravity or paste them in SQL Editor. The approved driver's password helper generates a SCRAM-SHA-256 verifier. The supplied password array is cloned because the driver clears it; the original is retained only for the subsequent test login and cleared afterward.

Official references: [Supabase roles](https://supabase.com/docs/guides/database/postgres/roles), [pooler and TLS](https://supabase.com/docs/guides/database/connecting-to-postgres), [pgJDBC API](https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/PGConnection.html).

## Steps

1. Open Windows PowerShell directly and run:

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
.\scripts\setup-runtime-role.ps1
```

2. At **Existing DATABASE administrator password**, enter the password chosen during Supabase project creation. This is not the staff account password or an API key.
3. At **NEW server-role password**, enter a unique password of at least 20 characters, already retained privately. Repeat it at the confirmation prompt. No visible characters is normal.
4. The program checks the role is still restricted and NOLOGIN, sets its SCRAM password and enables LOGIN with connection limit 5 in one transaction. It then opens a separate connection as smart_vrms_api, verifies TLS/role attributes, checks missing/nonmember context returns no business rows and checks rollback clears context on that same connection.
5. Expected:

```text
Restricted role provisioned. Retain its password privately; it was not saved locally.
SETUP-07 restricted login, verified TLS, no-identity denial and rollback context cleanup: PASS
```

6. Reply with the safe template. Actual staff password sign-in, refresh/logout and API/UI integration remain pending.

## Troubleshooting

- Provisioned but check failed: retain the new password and run the read-only retry below. A pooler may need time to recognize a newly enabled role; retry once after a short wait and then share only the sanitized result.

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
.\scripts\setup-runtime-role.ps1 -CheckOnly
```

- Provisioning not confirmed: check administrator password, certificate and active project. If the previous operation might have committed, use -CheckOnly rather than provisioning again. A lost commit acknowledgement can occur after a successful operation.
- Already enabled: provisioning deliberately refuses to reset an existing login. Use -CheckOnly with the retained role password. Password rotation requires a separate explicit procedure.
- Password mismatch/length: correct private entries and retry; no provisioning occurs.
- Missing certificate: restore local-config/supabase-ca.crt. Do not weaken TLS verification.
- No console: open Windows PowerShell directly rather than an editor output panel.
- SQL foundation check 001 expects NOLOGIN and is historical after this deliberate transition. Use this check after enabling the role; do not weaken permissions to make an obsolete stage check pass.
- Script execution policy blocks the script: report the error without changing machine policy.

## Safe reply

```text
DONE SETUP-07
Restricted role provisioned: yes/no
Restricted login/TLS/context checks: PASS / sanitized message
Server-role password retained privately: yes/no
```

The setup tool is compiled; driver SCRAM/password lifecycle and membership protocol tests pass locally. Live provisioning and login have not been performed by the agent. No shared database credential reaches the desktop and no API listener has been enabled.
