# Developer guide

Smart VRMS uses Java 17, Swing MVC, four Maven modules and a Core Java API with Supabase Auth/PostgreSQL. Read Release-Audit.md before assuming a feature is integrated.

## Fresh Windows checkout

Install prerequisites separately if absent; repository scripts do not install tools. With an existing JDK 17 and Maven:

```powershell
mvn -B package
.\scripts\package-desktop-windows.ps1
.\scripts\export-server-bundle.ps1
.\scripts\write-release-manifest.ps1
.\scripts\verify.ps1
.\scripts\run-preview.ps1
```

The first command resolves pinned Maven artifacts. Subsequent local scripts use offline Maven. Packaging creates the distribution needed by the historical Stage 9/10 file checks. The current manifest is computed from actual files before those checks run.

The harness includes pure functions, protocol fixtures, loopback HTTP and Swing renders. Some inherited suites use mock ports or document text; their PASS output must not be described as live provider or business acceptance. The independent ReviewRegressionChecks covers defects found in the source review. PackagedRuntimeChecks runs using the built image's own runtime/JARs and performs a read-only public GitHub TLS request. NativeWindowSmoke covers a real Swing window, keyboard traversal, resize and disposal.

## Boundaries

- contracts: plain Java contracts. application: ports and local validation.
- desktop: Swing UI/controller and HTTP adapters. Approved Gson handles JSON; tokens remain in memory. No server or PostgreSQL driver belongs in this module's distribution.
- server: fresh provider validation, authoritative membership, business adapters and API routes. Runtime role must be a non-owner without BYPASSRLS. Authorize inside each mutation transaction; a previously returned Staff object is not a transaction authorization.
- database: versioned migrations. Do not rerun applied migrations or apply edits to old migrations as a database upgrade.

Current ServerMain composes only authentication and membership. Its business ports remain disconnected. Several business adapters also need transaction/context and provider work. Complete their integration and tests before exposing them publicly.

## Configuration

Use the app's Connection settings to save only a public HTTPS Java API URL. DesktopConfig supports an isolated smartvrms.config.dir JVM property for tests. Unit tests must not overwrite actual profile settings. No tokens/passwords may be written into that file.

Server secrets belong only in protected operator-managed server configuration. local-config, environment files, build outputs, runtimes, archives and keystores are excluded from Git. The publishable Supabase key is public configuration, but do not print local files unnecessarily.

## Versions, CI and updates

AppVersion.CURRENT is the desktop release identifier. For a preview release, bump it and the packaging script's default version together, build/test, commit and push, then push a matching v<version>-preview tag. The pinned official GitHub Actions workflow rebuilds on Windows, runs checks, uploads CI artifacts, and publishes matching preview tags. A new source push does not rewrite an existing downloaded executable.

Stable releases are intentionally blocked in the workflow while production requirements remain incomplete. Once those gates are truly met, update the readiness rules, tests and release automation in a reviewed change. The desktop checks GitHub's latest stable-release redirect, validates the owner/repo/tag and opens the official release page only after user confirmation. It never installs update binaries automatically.

Do not add libraries/tools without the original approval procedure. Existing server libraries and the explicitly approved desktop Gson exception are recorded in Third-Party-Notices.md.
