# MANUAL ACTION REQUIRED — SETUP-03

## Purpose and prerequisites

SETUP-02 and DEP-02 were received. The existing project is `smart-vrms-dev` at `https://wvtnepxynjjbazczkukh.supabase.co`. Do not create another project or repeat dependency approval. Region was omitted, anonymous sign-in was answered `yes/no`, and pooler availability was unknown; these remain unverified.

This step enables read-only verification of the public Auth configuration. It uses your existing Free project, PowerShell and installed Java. No purchase, installation, database password, secret key, account creation, sign-in attempt, or SQL change is needed. Setup scripts are already in the project. The user-required manual configuration gate applies before live integration.

## Numbered steps — Windows

1. Open the [Supabase dashboard](https://supabase.com/dashboard), select your existing **smart-vrms-dev** project. In Authentication settings, locate **Allow anonymous sign-ins** and ensure it is **off**. Keep public signups off and email confirmation on. Save if changed. [Official Auth settings](https://supabase.com/docs/guides/auth/general-configuration).

2. In the project's settings, open **API Keys** and find the **Publishable key** beginning `sb_publishable_`. Copy that value. Do not select a secret key (`sb_secret_`) or legacy `service_role` key. The app intentionally rejects those key types. [Official key guide](https://supabase.com/docs/guides/getting-started/api-keys).

3. Open PowerShell as your normal user. Run:

   ```powershell
   Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
   .\scripts\configure-supabase.ps1
   ```

   At the prompt, paste the publishable key and press Enter. It is written only to the Git-ignored `local-config\supabase-public.properties`. The URL is already filled in by the script. No key needs to be pasted in chat. Expected: `Public configuration saved locally.` Existing files are never overwritten.

4. In the same PowerShell window, run:

   ```powershell
   .\scripts\check-supabase.ps1
   ```

   Expected output:

   ```text
   Public Auth settings reachable: yes
   Public signup disabled: true
   Email confirmation required: true
   Anonymous sign-in, region and Session pooler: verify in Dashboard.
   User authentication, membership, RLS and database connection: not tested.
   ```

   This command performs a GET of public Auth settings. It does not authenticate a user or change any provider settings. A successful settings check does not mean the application is fully connected.

5. In Supabase, open the project's **Connect** dialog. Look for a connection method labelled **Session pooler** and report whether it is offered. Do not send its connection string or password. Find the project region in the project's settings/overview and report its displayed name. Neither value is guessed from the URL. [Connection guide](https://supabase.com/docs/guides/database/connecting-to-postgres), [region guide](https://supabase.com/docs/guides/platform/regions).

6. Reply using the template below. After this check, the next implementation work is the membership schema and restricted database-role setup, then actual sign-in/membership/RLS verification. No schema migration has been applied yet.

## Troubleshooting

- `Public configuration already exists`: run the checker first. If the public key needs correction, open `local-config\supabase-public.properties` in Notepad and edit only `supabase.publishableKey=`. Never insert a secret/service-role key.
- Script execution policy blocks step 3: do not weaken security settings. Open Notepad, create `local-config\supabase-public.properties` under the project (create the folder if missing), choose **Save as type: All files**, encoding **UTF-8**, and write these two lines with your own publishable value:

   ```properties
   supabase.url=https://wvtnepxynjjbazczkukh.supabase.co
   supabase.publishableKey=PASTE_YOUR_SB_PUBLISHABLE_KEY_HERE
   ```

   To run the checker without a PowerShell script, use these individual commands from the project directory:

   ```powershell
   $repo = Join-Path $env:USERPROFILE '.m2\repository'
   $cp = 'server\target\classes;' + (Join-Path $repo 'com\google\code\gson\gson\2.15.0\gson-2.15.0.jar')
   java -cp $cp in.smartvrms.server.setup.AuthReadinessMain
   ```

- Invalid configuration: verify the file has the exact property names, no quotes around values, and a publishable key from this same project. Share only the generic error, not the file contents.
- “Sign in again with an active staff account” during this settings check: this is the adapter's safe 401/403 response; check that the publishable key belongs to this project. No staff credentials are required for settings.
- Service unavailable: check internet access and whether the Free project is paused, then retry once. Do not disable TLS or buy an add-on.
- `ClassNotFoundException`: confirm the working directory and run `mvn -o -B verify`, then repeat the checker. If Maven reports a missing artifact, report its name; do not select other versions.
- One of the boolean settings is `false`: correct the corresponding dashboard setting and rerun. Missing anonymous setting or pooler option: report what is missing; do not change unrelated settings.

The local public-config path is verified ignored by Git. This file is not a place for future database or signing secrets. Instructions for any privileged credentials will be provided only with the corresponding reviewed server setup.

## Safe reply

```text
DONE SETUP-03
Read-only checker output:
[paste only the lines printed by check-supabase.ps1]
Anonymous sign-in disabled: yes/no
Region:
Session pooler option available: yes/no/could not find
Sanitized error, if any:
```

Never include passwords, secret keys, service-role keys, tokens or password-bearing connection strings. Official documentation checked 25 October 2026; the agent has not inspected your logged-in dashboard.
