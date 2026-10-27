# SETUP-06 — First administrator and populated permission checks

## Purpose and prerequisites

Migration 001 succeeded according to the supplied screenshot; the user reports the exact SETUP-05 PASS result. The Data API exposed-schema confirmation is still outstanding and is step 1 below. This setup links one operator-controlled Auth account to the sole company with Administrator membership and an audit event. It does not enable application sign-in or a database login. No installation, paid service or deployment is required.

Use only project `wvtnepxynjjbazczkukh`, the isolated Free development project. This one-time bootstrap is an operator action; employees will later use administrator invitations. The `Smart VRMS` company display name is a beta workspace label, not a legal name or GST assertion.

Official sources: [Supabase user management](https://supabase.com/docs/guides/auth/managing-user-data), [Auth users](https://supabase.com/docs/guides/auth/users), [administrative account creation and email confirmation](https://supabase.com/docs/reference/javascript/auth-admin-createuser). No JavaScript SDK is introduced by these reference links.

## Steps

1. Open [Supabase Dashboard](https://supabase.com/dashboard) and verify project reference `wvtnepxynjjbazczkukh`. Under **Data API settings → Exposed schemas**, confirm `vrms_private` is absent. If present, stop and report it. Do not rerun migration 001.
2. Open **Authentication → Users**. If you already created your own intended administrator account, reuse it rather than making a duplicate. Otherwise select **Add user → Create new user**. Enter an email address you own and a strong unique password directly in Supabase. Keep the password private; it is distinct from the database password.
3. For this operator-created development account only, select **Auto Confirm User** when creating it. This is an administrative assertion that you control the address, not an email-delivery test. Keep the project's global email confirmation requirement ON, public signup OFF and anonymous sign-in OFF. Do not auto-confirm other people's accounts. If an existing intended account is unconfirmed or the dashboard offers different controls, report that before proceeding; do not edit auth tables directly.
4. Open the account details and copy its **User UID / ID**, a UUID. You do not need to send it in chat.
5. In Windows PowerShell run:

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
notepad '.\database\setup\002_bootstrap_administrator.sql'
```

6. Copy the entire file into a **new SQL Editor query**. In that query only, replace the zero UUID on the `admin_uid uuid :=` line with the UID from step 4. Keep the single quotes. Leave the later all-zero validation constant unchanged. Put no passwords, emails or keys in the SQL. Run the entire query as `postgres`. Expected: **Success. No rows returned.** It atomically creates one company, one Administrator membership and one bootstrap audit event; existing company/membership data is never overwritten.
7. Open the checks:

```powershell
notepad '.\database\verification\002_administrator_checks.sql'
```

8. Copy this entire file into another new SQL query and run it as `postgres`. No substitutions. Expected result:

```text
SETUP-06 administrator checks PASS; real login and employee tests remain pending
```

These checks use SET ROLE to exercise populated RLS as smart_vrms_api, including own membership/company reads, no-context/nonmember/wrong-company denial, blocked membership mutation, blocked audit reads, last-administrator protection and inactive-member denial. Temporary changes are rolled back. They do not authenticate over JDBC as the eventual runtime login, validate a live Auth token or test an Employee account.

## Troubleshooting

- Placeholder error: replace only the first `admin_uid` declaration's zero UUID in the SQL Editor copy, retaining its single quotes.
- User missing/unconfirmed/anonymous/deleted/banned: check the selected account in Authentication → Users. Do not change provider tables through SQL or disable global Auth restrictions.
- Company/membership already exists: do not rerun bootstrap or delete records. Run the verification file and share the result.
- Any SQL failure: stop; share only the sanitized error. If needed, run `ROLLBACK;` separately to clear an aborted transaction. Do not proceed with fragments after an error.
- Verification fails: keep sign-in disabled and report the error. No new role grants or RLS exemptions should be added as a workaround.
- No result row visible: check the SELECT output before the final ROLLBACK; report what is shown rather than treating generic success as the PASS result.

## Safe reply

```text
DONE SETUP-06
vrms_private absent from Exposed schemas: yes/no
My administrator Auth account created and confirmed: yes/no
Bootstrap: success / sanitized error
Administrator checks: PASS / sanitized error
Any setup problem: none / description
```

Do not include passwords, tokens, keys or connection strings. After this gate, continue with restricted server credentials, the Java membership adapter and real sign-in/session integration. No application feature is marked complete merely because bootstrap passed.

## Verification status

The two scripts were reviewed locally against migration 001. They have not been executed by the agent: this handoff supplies the missing live setup dependency. No Java or UI code changed, so the prior 357 Java checks remain historical evidence, not tests of these scripts. Full employee-role isolation, administrator handover/concurrency, provider revocation, JDBC context reuse and Storage tests remain outstanding.
