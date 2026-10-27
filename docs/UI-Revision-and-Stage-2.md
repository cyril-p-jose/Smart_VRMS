# Interface repair and Stage 2 entry

25 October 2026, Asia/Kolkata. User reported SETUP-01 complete, rejected the original visual quality, and authorized repairs followed by the next stage. This record supersedes the original Stage 1 visual and native-keyboard status.

## Delivered repair

Replaced vertically compressed BoxLayout cards with natural-height rows inside scrolling pages. Wrapped copy sizes to its available width. Booking actions remain in a separate footer. Input groups use two columns only when enough space is available. Unavailable actions have a distinct disabled style. The native title bar is retained.

Rebuilt sign-in, a grouped navigation rail (daily work, records, management), a rental desk organized by pickup/on-rent/return tasks, a searchable fleet list with profile illustration and shortlisting, and a two-step local rental draft/review journey. Fleet selection populates the same draft; editing review preserves input; clearing resets customer, dates, driver request and shortlist. Search with no result disables shortlist. Preview examples never become stored vehicles, customer identities, reservations or payments. There is no invented revenue, availability or successful authentication.

## Actual verification

- Offline Maven reactor: **BUILD SUCCESS**, Java 17, no new library or plugin download.
- Foundation harness: **30 assertions pass**, plus overlap/clipped-label traversal across **17 rendered views** at 1024×720, 1280×820 and 1600×1000. This includes scrolling content, required/strict date errors, preserved edit state and fleet-to-draft navigation.
- Inspected real Swing renders for sign-in, rental desk, fleet, minimum-size form and review with long input. Fixed blank nested fleet renderer labels and inconsistent navigation selection colors found during inspection.
- Native smoke: Tab moves from customer to contact, actual focus/resize/action location/window disposal pass at host 125% scaling. Process-only 100% and 150% Java scale overrides also pass; Windows settings were not changed. This is not a macOS/Linux device claim or a claim that every physical DPI configuration was tested.
- Previous native input failure reproduced in the restricted process and passed with the same test outside it. No assertion was removed to obtain success. SETUP-01 does not need repeating.

G1's known layout/native-input defects are resolved for continuation authorized by the user. Future design feedback remains actionable. Cross-platform device acceptance is still a later gate.

## Stage 2 started

Implemented 44 server-only capability rules for Administrator and Employee, based on baseline section 5. Missing/inactive membership, unknown action, and a different business fail closed with a generic denial. **237 permission assertions pass**, including employee denial for refunds, discounts, payroll, profit, configuration and invitations.

This is an internal policy unit, not authentication or a complete authorization system. No listener is active. Membership must later be read from the database after provider verification, never taken from editable client metadata. Resource ownership, assigned-document responsibility, standard-price/tax checks, last-admin protection and RLS are still required. Policy tests do not verify a live provider or database.

Stage 2 live integration now waits for **SETUP-02** and explicit approval of the reviewed dependencies. No dependencies were added, no accounts configured, and no SQL was applied. All 90 mandatory requirements remain tracked; none is marked complete merely from these screens.

## Evidence and launch

Current images and transcript: `docs/evidence/redesign/`. To launch in Windows PowerShell:

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
.\scripts\run-preview.ps1
```

To rerun all offline automated checks: `.\scripts\verify.ps1`. Native smoke commands remain in `docs/Stage-0-1-Report.md`; they require a normal interactive desktop process for OS key injection.
