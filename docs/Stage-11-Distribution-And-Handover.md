# Stage 11 — Application Distribution, Safe Updates, and Operator Handover

> Historical stage notes below describe evidence from their original review date. Current release evidence is in [Release-Audit.md](Release-Audit.md); do not use the older version numbers/checksums below for the 0.2.1-preview download.

## 1. Executive Summary & Verification Scope

Stage 11 records local packaging and handover checks. It does not establish that the current source has been published or that all target platforms are ready.

Current status (updated 27 October 2026):
- **`NFR-DIST-001`**: Windows x64 `0.2.1-preview` candidate packaged; macOS/Linux remain unbuilt/unverified.
- **`NFR-DIST-002`**: Local Windows package and verification checks run; live end-to-end business acceptance remains incomplete.
- **`NFR-DIST-003`**: Windows package is unsigned; macOS/Linux signing and native runner evidence are absent.
- **`NFR-DOCS-001`**: See the current release audit and operator guide for scope limits.
- **`NFR-PUB-001`**: Preview publication is authorized; use the versioned GitHub release after its CI publish job succeeds.

---

## 2. Platform Packaging & Runner Status (Gate G6)

| Target Platform | Package Format | Build / Test Evidence | Gate G6 Status | Code Signing Status |
| :--- | :--- | :--- | :--- | :--- |
| **Windows 11 x64** | `SmartVRMS-Windows-x64.zip` (31.9 MB)<br>`SmartVRMS.exe` native launcher | Built with `jlink` + `jpackage`; native process launch smoke, keyboard navigation, and memory checks passing. | **VERIFIED & READY** | **Unsigned** (Evaluation build; SmartScreen warning documented). |
| **macOS Apple Silicon (arm64)** | `.dmg` via `scripts/package-desktop-macos.sh` | Shell packaging script prepared; runner hardware not present on local Windows environment. | **PENDING RUNNER** | **Unsigned / Unnotarized** (Gatekeeper warning documented). |
| **macOS Intel (x64)** | `.dmg` via `scripts/package-desktop-macos.sh` | Shell packaging script prepared; runner hardware not present on local Windows environment. | **PENDING RUNNER** | **Unsigned / Unnotarized** (Gatekeeper warning documented). |
| **Ubuntu 24.04 x64** | `.deb` via `scripts/package-desktop-linux.sh` | Shell packaging script prepared; runner hardware not present on local Windows environment. | **PENDING RUNNER** | **Unsigned** (GPG repository signing pending). |

---

## 3. Package Cleanliness & Credential Isolation Audit

The packaged desktop bundle located in `dist/package-win64/SmartVRMS/app/` has been scanned and verified:

```text
dist/package-win64/SmartVRMS/app/
├── .jpackage.xml
├── SmartVRMS.cfg
├── application-0.1.0-SNAPSHOT.jar      (Pure Java domain interfaces & ports)
├── contracts-0.1.0-SNAPSHOT.jar        (Immutable domain records & DTOs)
├── desktop-0.1.0-SNAPSHOT.jar          (Native Swing views & REST clients)
├── gson-2.15.0.jar                     (Approved RFC 8259 JSON serialization)
└── error_prone_annotations-2.50.0.jar  (Approved compile-time annotations)
```

### Critical Exclusions Verified:
1. **Zero Server Bytecode**: `server.jar` and all `in.smartvrms.server.*` classes are completely omitted from the client package.
2. **Zero Database Drivers**: `org.postgresql:postgresql` JDBC driver is excluded.
3. **Zero Secrets or Credentials**: No `.env`, `application.properties`, Supabase service-role keys, database passwords, or JWT secrets are present.
4. **Zero Keystores / Private Keys**: No `.jks`, `.p12`, or `.key` files are packaged.

---

## 4. Upgrade Safety & Local Configuration Lifecycle

User connection preferences are decoupled from the application binary directory:
- **Windows**: `%LOCALAPPDATA%\SmartVRMS\config.properties`
- **macOS**: `~/Library/Application Support/SmartVRMS/config.properties`
- **Linux**: `~/.config/smart-vrms/config.properties`

### Safe Upgrade Guarantee:
Extracting a newer release version (or overwriting files in the application folder) has **zero impact** on the user's saved API endpoint URL.

### Session Token Security:
Tokens are held **strictly in RAM** inside `DesktopSession`. When the user closes the application, tokens are discarded. No access tokens, refresh tokens, or passwords are ever written to disk.

---

## 5. Non-Silent Update Flow (NFR-PUB-001)

Smart VRMS implements an explicit, non-silent update protocol:
1. **Background Query**: On application startup or when the user clicks **"Check for updates"**, `UpdateChecker` performs an asynchronous `HEAD` request to `https://github.com/cyril-p-jose/Smart_VRMS/releases/latest`.
2. **SemVer Comparison**: The redirected tag is extracted and compared against `AppVersion.CURRENT` (`0.1.2-preview`). Spoofed domains, HTTP URLs, query parameters, and fragments are rejected.
3. **User Notification**:
   - If a newer release is detected, the footer button updates to **"Update available — download"**.
   - If clicked, a dialog prompts: *"A newer version is available. Open its official download page?"*.
4. **Browser Handoff**: If confirmed, the system default browser navigates to the official GitHub release page.
5. **No Silent Execution**: The application **never** downloads binaries in the background and **never** auto-executes installers.

---

## 6. Offline & Backend Sleep Resilience

1. **Cold-Start / Free-Tier Sleep Handling**:
   - On the Render free tier, web services sleep after 15 minutes of inactivity.
   - Initial sign-in requests trigger a graceful waiting state; upon wake-up (~50–70s), authentication completes normally.
2. **Offline Launch**:
   - Launching without internet connectivity does not crash the desktop application.
   - The UI cleanly opens in preview mode ("Smart VRMS • Foundation preview") and informs the user when connection attempts fail.

---

## 7. Publication Gate G7 & Release Candidate State

This version/checksum table is a historical snapshot and does not identify the current 0.2.1-preview artifact.

Per `docs/Release-Manifest.md`, public binary release remains in preview status:
- **Current Version**: `0.1.2-preview`
- **Status**: `PREVIEW PUBLICATION AUTHORIZED; PRODUCTION BLOCKED` pending complete business lifecycle integration.
- **Binary Checksum**:
  - `SmartVRMS-Windows-x64.zip`: `7C93EAAE9067130DADD81BB3EA12F7F7B5D95A95BE1FCB65A4CD82F1369C93E3`
- **Safety Gate**: No Git commits, tags, or GitHub release assets may be published without explicit operator authorization.
