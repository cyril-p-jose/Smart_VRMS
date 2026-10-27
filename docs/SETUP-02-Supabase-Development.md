# MANUAL ACTION REQUIRED — SETUP-02

**Response received; do not repeat project creation or DEP-02 approval.** User confirmed the Free isolated project and approved all four artifacts. Region, anonymous sign-in and pooler availability were not confirmed. Continue with `SETUP-03-Public-Connection.md` to finish those checks and configure the publishable key locally.

## A. Purpose

Provide an isolated Supabase development project for real authentication, membership, RLS and private-storage integration tests. A local UI and Java policy tests cannot verify these services. The user must create/configure the account and retain credentials; no account or billing access is available to the agent. Live Stage 2 work waits for this response and DEP-02 approval.

## B. Prerequisites and cost

Use an existing Supabase login or create one in the [official dashboard](https://supabase.com/dashboard). Use a **Free-plan organization**, not a paid organization. No CLI, Docker, PostgreSQL installation, hosting purchase or administrator rights are needed for this step. Current Free plan is $0/month, limits active free projects, and pauses inactive projects. If the dashboard requires upgrading or a paid add-on, stop and report it. [Official pricing](https://supabase.com/pricing), [Free organization guidance](https://supabase.com/kb/troubleshooting/keeping-free-projects-after-pro-upgrade-Kf9Xm2).

Keep the database password in your password manager. Never send passwords, secret/service-role keys or connection strings containing passwords in chat. No secret file is needed yet; the server is not connected and its secure configuration loader will be delivered before credentials are used.

## C. Numbered procedure

1. Open the dashboard in your browser and sign in. Select an existing Free organization or create a new Free organization named `Smart VRMS Development`. Confirm the plan explicitly says Free. Do not change an existing organization's plan.

2. If you already created an **empty, dedicated development project** for this app, reuse it and report its name. Otherwise start a new project in that Free organization. Name it `smart-vrms-dev`. Set a strong generated database password and save it privately. Choose the nearest available India region for Kerala (Mumbai if offered); report the exact region selected. If only a general region is offered, report that label. Do not reuse a live-business database. [Platform guide](https://supabase.com/docs/guides/platform), [region guide](https://supabase.com/docs/guides/platform/regions).

3. Wait for provisioning to finish. Open the project and verify its services are ready. Confirm it contains no real customer/personnel/financial data. Do not import demo SQL or expose an application schema yet; the reviewed migrations and grants come next.

4. Open the project's Authentication configuration. Disable **Allow new users to sign up** and **Allow anonymous sign-ins**. Leave email confirmation enabled. The approved app uses administrator invitations. Save and reopen the settings to check they persist. Dashboard grouping can change; the official general-configuration guide links to the relevant settings. If a switch is absent, report it rather than changing unrelated options. [Official Auth configuration](https://supabase.com/docs/guides/auth/general-configuration).

5. Find the project URL in the project's connection/API information. Record only the public HTTPS URL, normally `https://YOUR_PROJECT_REF.supabase.co`. Locate the publishable key for later use but do not send it or any secret key. Keep database credentials private. Do not put a service-role/secret key into the desktop app. [Official API key guide](https://supabase.com/docs/guides/getting-started/api-keys).

6. Open the project's **Connect** dialog and confirm a **Session pooler** connection is offered. Note only whether it is available. This avoids requiring a paid IPv4 add-on for the eventual Java server; the direct database endpoint can require IPv6. Do not paste its password-bearing connection string. [Official connection guide](https://supabase.com/docs/guides/database/connecting-to-postgres).

7. Read [DEP-02](Stage-2-Dependency-Review.md), then approve or decline the four exact artifacts in your reply. No terminal command installs these during setup. After approval, the agent will add the scoped dependencies, build the actual Auth/database adapters and provide the next secure configuration/migration procedure. First-admin provisioning, restricted application DB role, private Storage, sender configuration and tests remain pending until that work is ready.

8. Optionally reopen the revised local preview to inspect the delivered repair. Windows PowerShell; exact working directory and command:

   ```powershell
   Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
   .\scripts\run-preview.ps1
   ```

   Expected: grouped navigation, Fleet → Use in rental draft → details → Review draft → Edit details. Real sign-in/confirmation remain unavailable. No secret is needed for preview. To run all implemented checks instead: `.\scripts\verify.ps1` (30 foundation assertions and 237 permission assertions expected).

## D. Verification and troubleshooting

- Confirm Free plan, provisioned project, separate development data boundary, disabled public/anonymous signup, HTTPS project URL and Session pooler availability. These are setup facts, not successful API/RLS tests.
- No free project slot: stop and report. Do not delete another project, change its plan or use production data to make room.
- Provisioning error: retain the project, refresh its status and report the sanitized dashboard error. Do not repeatedly create paid replacements.
- Different Auth setting labels: report the label and what is missing; do not paste credentials or a secret-bearing screenshot.
- IPv6 issue: note Session pooler availability; do not buy IPv4 or disable TLS.
- PowerShell script policy block: run `java -cp 'contracts/target/classes;application/target/classes;desktop/target/classes' in.smartvrms.desktop.DesktopMain` from the project directory. Do not weaken execution policy.

Official links were checked 25 October 2026. The account dashboard itself has not been inspected by the agent; no unseen UI state is claimed.

## E. Safe reply

```text
DONE SETUP-02
Project name:
Plan: Free
Region:
Public project URL:
Dedicated development project with no real business data: yes/no
Public signup disabled: yes/no
Anonymous sign-in disabled: yes/no
Email confirmation enabled: yes/no
Session pooler available: yes/no
DEP-02: approve the four exact artifacts / decline
Sanitized error, if any:
```

The agent will wait before dependent live integration. No passwords, tokens, secret keys or private connection strings belong in this reply.
