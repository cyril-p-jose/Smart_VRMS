# SETUP-13 — Backup and recovery status

**Status: not implemented for live business data.** `BackupAndRecoveryManager.createBackup(...)` is disabled because its legacy output was plaintext and did not copy Supabase Storage objects. The local archive checker verifies synthetic file hashes only; it does not restore a database or measure recovery time. Do not use it for company/customer records.

Current source does not provide an automated backup destination, encryption key management, scheduled export, full Storage copy, or isolated restore rehearsal. Do not report an RPO/RTO until a real restore has been timed and reconciled.

## Official provider references

- Supabase database backups: https://supabase.com/docs/guides/platform/backups
- Supabase Storage object downloads: https://supabase.com/docs/guides/storage/management/download-objects

Supabase's database backup documentation says Free-plan projects should regularly export data and maintain off-site backups, and notes that database backups do not include Storage objects. Verify the current project plan and dashboard state directly; do not rely on an old fixed price or an assumption that a dashboard backup is downloadable.

## Owner check — beginner steps (Manglish)

1. Browser-il ningalude Supabase project open cheyyuka: https://supabase.com/dashboard.
2. `smart-vrms-dev` project select cheyyuka. Production business data undenkil backup test cheyyunnathinu munpu nirthuka; ippo ningal project-il real business data illa ennu paranjirunnu, ennal athu ippolum shari aano ennu dashboard-il verify cheyyuka.
3. `Project Settings`-il plan/billing status nokkuka. Athu screenshot/chat-il share cheyyenda; `Free`/other plan ennu mathram note cheyyuka.
4. Left menu-il `Database` → `Backups` open cheythu dashboard-il entha backup/recovery option kaanikkunnathennu nokkuka. Available option aanu ennu mathram parayuka; backup restore cheyyaruthu.
5. `Storage` → `Files` open cheythu bucket undo ennu nokkuka. Database backup mathram files/photos copy cheyyilla; storage objects-inu separate safe copy venam.
6. CLI/tool install cheyyan, paid storage undakkan, GitHub secrets configure cheyyan, allenkil live project restore cheyyan ippo ee guide authorize cheyyunnilla. Athinu munpu cost, encryption, access, exact restore target review cheyyanam.

**Expected result:** current plan/backup options/storage bucket visibility ariyam; oru file/record polum modify cheyyilla.

**Safe reply:** `SETUP-13 CHECK DONE — Plan: Free/other; Database Backups page: available/not available; Storage bucket: present/absent; no restore performed.` Screenshot-il email, project secrets, database host/user/password, API key undenkil share cheyyaruthu.

No external backup provider, paid plan, GitHub secret, migration, or restore was configured as part of this status check.
