# MANUAL ACTION REQUIRED — SETUP-01

**Historical handoff — user reported done on 25 October 2026. Do not repeat.** The user rejected the original layout, which has since been rebuilt. Native Tab checks now pass outside the restricted process at host 125% and process scale overrides 100%/150%. See `UI-Revision-and-Stage-2.md`; the next pending handoff is SETUP-02. The original steps below are retained for history and refer to the original interface.

## A. Purpose

Review the real Stage 1 Swing interface before its patterns are reused, and verify native keyboard handling. The automated Windows test opened and resized a real window at 125% scale, but injected Tab did not advance focus. Its focus policy identifies the correct next field. We must distinguish a native input/test-environment limitation from an application defect using a human keyboard check. Stage 2 and reuse of these UI patterns wait for G1 review.

This handoff is required by the user's development/release procedure (sections 3, 5, 6) and the approved G1 representative rendered UI gate. It does not request another planning approval.

## B. Prerequisites and cost

Existing Windows 11 computer, PowerShell, JDK 17 and Maven; all were found and used. No account, secret, software installation, administrator rights or paid service is needed. Allow about 5 minutes. Official references: [Oracle Swing focus guidance](https://docs.oracle.com/javase/tutorial/uiswing/misc/focus.html), [Maven command options](https://maven.apache.org/ref/3.9.16/maven-embedder/cli.html), [Microsoft Windows display settings](https://support.microsoft.com/en-us/windows/change-your-screen-resolution-and-layout-in-windows-5effefe3-2eac-e306-0b5d-2073b765876b).

## C. Numbered procedure — Windows PowerShell

1. Open PowerShell as your normal user. Navigate to the exact project directory:

   ```powershell
   Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
   ```

2. Build using the already installed cached tools:

   ```powershell
   .\scripts\verify.ps1
   ```

   Expected: Maven `BUILD SUCCESS`, then `PASS: 27 assertions; 16 Swing renders`. If script policy prevents running the file, do not weaken your execution policy; run these equivalent commands individually, stopping on any nonzero exit:

   ```powershell
   mvn -o -B verify
   javac --release 17 -encoding UTF-8 -cp 'contracts/target/classes;application/target/classes;desktop/target/classes' -d verification/generated verification/FoundationChecks.java
   java '-Djava.awt.headless=true' -cp 'contracts/target/classes;application/target/classes;desktop/target/classes;verification/generated' FoundationChecks
   ```

3. Launch the actual application:

   ```powershell
   java -cp 'contracts/target/classes;application/target/classes;desktop/target/classes' in.smartvrms.desktop.DesktopMain
   ```

   Expected: a native window titled `Smart VRMS • Foundation preview`. The shell command stays active until the window closes. Sign-in fields and sign-in action are unavailable; no credentials are needed.

4. Click **Explore interface preview**, then **New booking**. Click Customer name, type `Example customer`, and press Tab once. Expected: focus moves to Contact number. Shift+Tab should return to Customer name. Continue Tab through the editable date fields and controls. Report the field/control reached if it differs. Use fictional data only.

5. Enter phone `9876543210`, pickup `25/10/2026 09:00`, return `26/10/2026 09:00`. Activate **Check draft fields** using the keyboard. Expected: local-validity message explicitly states nothing is saved or booked. Change return to `24/10/2026 09:00`; expect return-after-pickup feedback. Clear draft; expect all editable inputs reset. Confirm booking must remain disabled.

6. Inspect **Overview**, **Vehicles**, **New booking**, and the sign-in view. In Vehicles, each example button should change the detail title. Resize down to the minimum allowed window size, scroll the booking form, then maximize. Check that controls do not overlap and draft actions remain visible. Report cropped text or inaccessible controls. Review overall typography, spacing, colors and navigation; say approve or list concrete changes.

7. Report your current Windows display scaling from Settings → System → Display. Check the same layouts at your existing scale; no system-setting change is required. Other scale/device checks stay pending when unavailable. Close using the normal title-bar close control; expected: the Java command exits and PowerShell prompt returns. Relaunch once and confirm no draft data was persisted.

## D. Verification and troubleshooting

- `ClassNotFoundException`: confirm the working directory and complete step 2 successfully. Do not run from the desktop parent folder.
- Offline missing artifact: stop and provide the artifact name and sanitized Maven error. Do not install dependencies or switch to an unreviewed download.
- Startup says invalid API endpoint: if you previously configured `SMART_VRMS_API_URL`, remove it for this PowerShell session only with `Remove-Item Env:SMART_VRMS_API_URL -ErrorAction SilentlyContinue` and retry. Do not send the value to chat.
- Tab does not advance: confirm the application window is active and a text caret appears in Customer name. Retry once; report the exact behavior. This blocks G1 and will be investigated; do not mark the check passed.
- A visible preview is not a successful authentication or a saved booking. No real customer data is needed.

## E. Safe reply template

```text
DONE SETUP-01
Windows version / display scale:
Build result:
Tab and Shift+Tab results:
Draft validation / clear results:
Small-window / scrolling / maximize results:
Close and relaunch result:
Visual review: approve / requested changes
Sanitized error, if any:
```

Do not include passwords, keys, tokens, or private customer information. The agent will wait for this response before dependent work.
