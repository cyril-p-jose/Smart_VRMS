# Current status correction

Current public preview is `0.1.2-preview`: hosted staff sign-in and real fleet reading passed operator acceptance. **Production remains blocked** because booking, return, financial, personnel, file, email and reporting journeys are not fully connected and the independent production gates remain open. The readiness notes below are historical and must not override the current audit or 90-item traceability matrix. See docs/Release-Audit.md and docs/Business-Prototype-Progress.md for current evidence and blockers.

# Smart VRMS — Submission Readiness & Integration Review (Stage 8)

> **Historical record only.** Completion, restore, RTO/RPO, and end-to-end language in this archived Stage 8 report is superseded. The checks described here used local fixtures and did not connect to the live business database or perform a real restore. Use the current traceability matrix and release audit for present status.

**Deliverable Status**: Submission Candidate / Working Prototype (Pre-Release)
**Date**: 25 October 2026, Asia/Kolkata
**Target Platform**: Windows 11 x64, OpenJDK 17.0.12, Apache Maven 3.9.8
**Architecture**: Four-module Core Java 17 + Swing MVC + Supabase PostgreSQL Backend
**Gate Status**: Historical claim superseded; live end-to-end/recovery gate remains open.
**Evidence**: `docs/evidence/stage8-runtime/local-verification.txt` (1,070 assertions across 14 test suites passed)

---

## 1. Executive Summary & Honest Release Labeling

In accordance with foundational rules and development contracts:
- **Scope Status**: Historical statement, not current evidence; full business workflows are not live-accepted.
- **Release Label**: This artifact is strictly labelled as a **Submission Candidate / Prototype**. It is **not** a final public release because Stage 9 (Multi-Platform Native `jpackage` packaging for macOS/Linux, headless 24/7 worker hosting) and Stage 10 (Public download publication, SHA256 release distribution) require explicit deployment authorization and external build runners.
- **Traceability**: This archived snapshot's **84 Complete / 6 Planned** count is obsolete. The current tracker preserves 90 IDs and records remaining delivery work as in progress pending real acceptance.

---

## 2. Tested Launch Commands

### A. Run Full Verification Suite
Executes reactor build and all 14 integration suites (1,070 automated assertions):
```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```
*Expected Result*: `Reactor Summary: SUCCESS`, all 14 stages pass, exit code 0.

### B. Launch Native Desktop Swing Application (Preview & Connected Modes)
```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
powershell -ExecutionPolicy Bypass -File .\scripts\run-preview.ps1
```
*Expected Result*: Responsive native Swing window opens with:
1. Staff Sign-In form (`email`, `password`, `Inspect Foundation Preview`).
2. Two-pane layout with grouped navigation rail (`Daily Work`, `Records`, `Management`).
3. Responsive layouts across 1024x720, 1280x820, and 1600x1000 display resolutions.

### C. Launch Local API Server (Restricted Runtime)
```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
. .\scripts\server-classpath.ps1
java -cp $serverClassPath in.smartvrms.server.ServerMain
```
*Expected Result*: Fails closed safely if credentials are not configured; binds to loopback interface when configured.

---

## 3. Exact Demonstration Journeys (Fictional Dev Dataset)

All demonstration scenarios execute on strictly fictional development data without personal data or real payment credentials:

### Journey A — Complete Rental Lifecycle
1. **Customer & Visit Registration**:
   - Customer: *Vikram Malhotra* (`+919876543210`, `vikram.malhotra@fictional-domain.in`, Kolkata).
   - Age at visit: `32` (captured with `CURRENT_DATE`, satisfying constraint `[18, 120]`).
   - Created atomically inside booking confirmation workflow (`FR-CUST-001`, `FR-CUST-002`, `FR-CUST-008`). Standalone creation blocked.
2. **Quote & Advance**:
   - Vehicle: Hyundai Creta (Car, `WB-01-AB-1234`), 3 days (72 hours).
   - Rate: ₹2,400/day. Quote = ₹7,200 (720,000 paise).
   - Agreed advance = ₹2,000; cash received and verified (`FR-PAY-001`, `FR-BOOK-008`). Balance due = ₹5,200.
3. **Atomic Booking & Outbox**:
   - Booking `BK-20261025-XXXX` confirmed atomically (`FR-BOOK-001`).
   - Transactional outbox queues confirmation email with pro-forma PDF attachment (`FR-BOOK-011`, `FR-MSG-001`).
4. **Checkout & Running Extension**:
   - Checkout recorded: 10,500 km, 100% fuel, condition `ACCEPTABLE` (`FR-OPS-002`).
   - Extension +24h requested: full window repriced (4 days = ₹9,600), delta charge = ₹2,400 posted without repeating advance deduction (`FR-EXT-001`, `FR-EXT-002`).
5. **Return, Final Settlement & Review**:
   - Return inspection: 10,850 km, 90% fuel, condition `ACCEPTABLE`. Vehicle released to `AVAILABLE` (`FR-OPS-003`).
   - Final settlement: remaining debt (₹7,600) paid in full (`FR-FIN-001`). Balance due = 0 paise.
   - Voluntary customer review recorded: 5 stars, "Excellent journey and well-maintained vehicle." (`FR-REV-001`).

### Journey B — Returning Customer & Multi-Vehicle Package
1. **Customer History & Repeat Visit**:
   - Lookup existing customer *Vikram Malhotra* by phone (`FR-CUST-005`). Reused Customer ID.
   - New distinct Visit ID created; historical first visit preserved intact (`FR-CUST-003`, `FR-CUST-004`, `FR-CUST-006`).
2. **Multi-Vehicle Package & Mixed Driver Needs**:
   - Package: Hyundai Creta (Car) + Honda Activa (Scooter, `WB-01-SC-5678`) for 2 days (`FR-BOOK-002`).
   - Driver assigned to Car (*Ramesh Kumar*); Scooter is self-drive (`FR-BOOK-003`).
   - Consolidated quote: Car ₹4,800 + Scooter ₹1,200 + Car Driver ₹1,600 = ₹7,600 (760,000 paise).
   - Refundable security deposit: ₹5,000 recorded as balance-sheet liability, not revenue (`FR-FIN-001`).
3. **Partial Return & Active Badge Continuity**:
   - Car returned on Day 2; Scooter kept until Day 3 (`FR-EXT-003`).
   - Customer active rental badge remains visible because Scooter is still on rent (`FR-CUST-007`).
   - Scooter returned on Day 3, clearing customer active badge.

### Journey C — Damaged Return, Maintenance & Safety Gates
1. **Damage Inspection**:
   - Maruti Swift (`WB-01-SW-9999`) returned with damaged rear bumper and cracked tail light (`CUSTOMER_DAMAGE`).
   - Custody inspection records condition `DAMAGED`, damage notes, and estimated repair cost ₹8,500 (`FR-DMG-001`).
2. **Safety Gates & Driver Release**:
   - Vehicle operational status transitions to `MAINTENANCE` with active `asset_restrictions` entry (`FR-DMG-003`).
   - Assigned driver is released independently to `AVAILABLE` (`FR-DRV-003`).
3. **Conflict Surfacing & Resolution**:
   - Overlapping future confirmed booking exists: surfaced as reassignment alert without deleting the contractual reservation (`FR-DMG-004`).
   - Garage repairs completed, actual expense posted (₹8,000) (`FR-DMG-002`).
   - Administrator resolves maintenance case (`resolveMaintenanceCase`), removing restriction and restoring vehicle to `AVAILABLE` (`FR-DMG-003`). Employee resolution attempts are rejected with HTTP 403 Forbidden.

### Journey D — Driver Leave, Indian Financial Year & Privacy Isolation
1. **Driver Leave Conflict**:
   - Driver *Suresh Roy* approved on leave for 2026-11-20 to 2026-11-22 (`FR-EMP-003`).
   - Booking attempt requesting Suresh Roy on 2026-11-21 is rejected due to leave conflict (`FR-DRV-002`).
2. **Compensation Tracking**:
   - Driver trip extra compensation (₹1,500) recorded and linked to company expenses without statutory payroll deductions (`FR-EMP-006`, `FR-EMP-007`).
3. **Indian FY & Privacy Isolation**:
   - Indian Financial Year calculated as `FY 2026-27` (1 April 2026 to 31 March 2027) with Monday weeks (`FR-FIN-003`).
   - Operational report accessed by Employee: shows fleet utilization, zero compensation or company margin fields (`FR-ACC-003`, `FR-FIN-005`).
   - Financial report accessed by Employee: strictly denied with HTTP 403 Forbidden.
   - Financial report accessed by Administrator: reconciles revenue, cash collections, liabilities, expenses, and operating margin with RFC 4180 CSV export.

### Journey E — Document Expiry Window & Superseded Reminders
1. **72h Expiry Boundary**:
   - Insurance document expiring in 48 hours is classified as `LESS_THAN_72H` by deterministic scanner (`FR-DOC-003`).
   - Exactly 72h00m (259,200s) is strictly excluded (`NONE`).
   - Outbox notification queued once for responsible fleet staff.
2. **Renewal Invalidation**:
   - Document renewed before email send (new version ID generated, `FR-DOC-002`).
   - Outbox worker detects version renewal, invalidating and superseding the pending reminder message (`FR-DOC-004`).
   - Non-expiring documents (e.g. Registration Certificate) schedule zero reminders.

---

## 4. Historical fixture archive check — not a recovery drill (`NFR-REC-001`)

The old test created synthetic table JSON and compared hashes/counts in a temporary directory. It did not call a live database, copy Storage objects into a backup, restore data, or measure RTO/RPO. `BackupAndRecoveryManager.createBackup(...)` is now disabled for live data because the legacy export was plaintext and incomplete. NFR-REC-001 remains not implemented.

---

## 5. Measured Performance & Capacity Baseline (`NFR-PERF-001`)

Benchmarked under standard JVM test runner without fabricated numbers:
- **Quote Engine Throughput**: 1,081,221 operations/sec (5,000 quote calculations in 4 ms).
- **Average Quote Latency**: 0.0009 ms (target < 1.0 ms).
- **Idempotency Key Verification**: < 1.0 ms under concurrent load.
- **JVM Working Heap Memory**: 12 MB (target < 128 MB).

---

## 6. Secret & Credential Audit (`NFR-SEC-001`, `NFR-SEC-002`)

- Repository-wide cryptographic and credential scanner executed across 184 files in `contracts`, `application`, `desktop`, `server`, `database`, `scripts`, and `docs`.
- Evaluated regex patterns for `sb_secret_`, `service_role`, raw database connection URIs, and private RSA/SSH keys.
- **Audit Outcome**: **0 leaks detected**. All credentials remain Git-ignored under `local-config/` or in private terminal prompts.

---

## 7. Native UI & Layout Verification (`NFR-UI-001`, `NFR-UI-002`)

- **Render Testing**: All core screens (`SignInView`, `WorkspaceView`, `BookingView`, `PersonnelView`, `RunningServicesView`, `FinanceView`, `AutomationAndReportsView`) rendered and laid out under headless and native Swing environments.
- **Resolution Matrix**: Verified at **1024x720**, **1280x820**, and **1600x1000** display resolutions.
- **Inspection**: Zero component clipping, zero text truncation, proper scroll pane encapsulation, and valid keyboard focus traversal policies.

---

## 8. Remaining External Prerequisites for Final Release (Stages 9 & 10)

The following items remain strictly planned and require subsequent user authorization:
1. **Stage 9 (Hosted Topology & Packaging)**:
   - Free Java API/worker hosting environment deployment (e.g. Render, Railway, or VPS) with laptop-off cron execution.
   - Cross-platform native application bundles via JDK `jpackage` (Windows `.msi`/`.exe`, macOS `.dmg`, Linux `.deb`).
   - Publisher code signing certificates and notarization.
2. **Stage 10 (Release Publication)**:
   - Production deployment review and live data verification.
   - Generating SHA256 checksums and hosting downloadable installers on approved distribution channels.
