# Stage 2 Auth preparation — 25 October 2026

## Received and recorded

The user confirmed an isolated Free development project named `smart-vrms-dev`, provisioning finished, no real business data, public signups disabled and email confirmation enabled. Public URL: `https://wvtnepxynjjbazczkukh.supabase.co`. Region, anonymous sign-in status and Session pooler availability remain unconfirmed. The user explicitly approved Gson 2.15.0, pgJDBC 42.7.14, error_prone_annotations 2.50.0 and checker-qual 3.55.1.

## Implemented and verified

Added the four approved dependencies to the server Maven dependency graph; none is added to the desktop. Resolved graph contains exactly those four external JARs. Their downloaded SHA256 values match the dependency review. No application framework, tool installation, paid service, deployment or database mutation occurred.

Implemented public Supabase configuration validation, strict bounded JSON handling (including duplicate-key rejection), HTTPS provider user verification, public-settings retrieval, capped response buffering, full-response deadlines, no redirect following or application retry, and sanitized failures. Provider metadata cannot assign application roles: user verification returns only a UUID; authoritative membership is still required. Anonymous, banned, deleted, unconfirmed/malformed identities fail closed. No sign-in UI or protected API endpoint is enabled by these classes alone.

Added `configure-supabase.ps1` and `check-supabase.ps1` for local public-key configuration and read-only diagnostics. Configuration is Git-ignored, refuses secret/service-role keys, preserves existing files and never requests a password. The public key must still be supplied by the user. No local configuration was fabricated.

Actual checks:

- Maven reactor build passes.
- 30 UI/foundation assertions and 17 renders pass unchanged.
- 237 Java permission assertions pass.
- 80 Auth/config/JSON/HTTP assertions pass. They cover request headers, malformed/anonymous/banned/deleted identities, strict JSON, depth and byte limits, duplicate fields, safe errors, no redirects, cancellation and full response timeout. Provider responses are fixtures; HTTP transport tests use a real loopback server.
- Restricted process could not open loopback sockets. The identical suite passed outside the sandbox; no checks were removed.
- Uncredentialed HTTPS GET to the user's `/auth/v1/settings` returned **401**. This confirms endpoint reachability only, not project settings or user authentication.
- Maven dependency tree and exact four artifact hashes inspected. Secret/public config path confirmed ignored by Git.

No live authenticated Supabase request, password sign-in/refresh/logout, membership lookup, SQL migration, JDBC connection, RLS policy, private Storage, invite, first-admin provisioning or cross-platform integration acceptance is claimed. pgJDBC is approved and resolved for the immediately upcoming database connection stage; no database request has run yet. Third-party license bundling remains a packaging check.

## Current dependency gate

**SETUP-03** is the concrete next manual action. It gathers the publishable key locally and checks public Auth settings; it also resolves the three incomplete setup facts. The user need not create another project or approve the same libraries again. Subsequent privileged setup will use private server configuration and a separate migration/restricted-role procedure.

Evidence: `docs/evidence/stage2-auth/`. Auth protocol reference: [official Supabase Auth OpenAPI](https://github.com/supabase/auth/blob/master/openapi.yaml). This implementation uses Java HTTP, not the JavaScript SDK.
