# Stage 0/1 implementation evidence

Date: 25 October 2026, Asia/Kolkata. **Stage 0 complete; Stage 1 implemented for review, G1 pending.**

## Actual starting state and preservation

Workspace contained only five planning artifacts. No source, build, AGENTS.md or `.git` repository existed. `git status --short --branch` initially returned “not a git repository”. Initialized an empty local `main` branch; no commit or remote. Original artifacts retain their SHA256 values in the approval record. Generated outputs, logs and local secrets are ignored.

Installed tools actually executed: Temurin OpenJDK/javac **17.0.20.1+1** (x64), Maven **3.9.16** from the existing mvnd distribution, Git **2.56.0.windows.2**, Windows 11 amd64. No tools installed. Maven initially emitted “Access is denied.” before its successful output. After adding the project-local `.mvn/jvm.config` (UTF-8 JVM option and project boundary), the final offline build no longer emitted that warning. No system configuration was changed.

## Implemented

- Four Maven modules, Java 17 release compilation, UTF-8 source/build settings; installed cached resource/compiler/Surefire/JAR plugins pinned to 3.4.0/3.13.0/3.2.5/3.3.0. No new application/test libraries or plugin downloads.
- Shared Swing typography/color/spacing/control patterns; native decorated frame; sign-in view; explicit preview shell/navigation; empty overview; fictional vehicle gallery/details; booking draft/financial-summary layout.
- Controller delegates draft validation to application service; contracts and application have no Swing. No SQL/HTTP/authoritative pricing in views. Server fails closed with no listener. Unavailable authentication adapter cannot authenticate.
- Strict dates, required inputs, positive rental period, local draft reset, unavailable confirmation. Public HTTPS endpoint validation rejects userinfo/query/fragment and redacts invalid values. Generic JDK logging; no sensitive input logging or persistence.

## Executed checks

`scripts/verify.ps1`: offline `mvn -o -B verify` passes all five reactor entries (parent plus four modules); four JARs generated. Surefire has no tests; the separate Java harness passes **27 assertions** and generates **16 actual Swing component renders** at 1024×720, 1280×820 and 1600×1000. These are offscreen render sizes, not proof of physical display scaling.

Assertions cover required fields, invalid calendar dates, zero/negative period, Unicode input, HTTPS config/redaction, disabled authentication/booking, no fake saved result, EDT guard, clearing, horizontal overflow and action visibility. Visual inspection found horizontal booking overflow at the smallest width; changed to width-tracking scroll content, then rebuilt and added overflow/action regression checks. Representative sign-in, fleet and booking renders were inspected.

`NativeWindowSmoke`: real frame launched at the host's **125%** scale. Initial field focus, resizing, draft-action location, maximize request and disposal passed. OS-injected Tab did **not** move focus to the contact field. The Swing focus policy identifies the contact field as the next component, but native key delivery remains unresolved. Check deliberately exits nonzero; G1 cannot close until SETUP-01 establishes whether human keyboard navigation works or a defect needs fixing. A maximize request alone is not a complete maximized layout acceptance test.

Native smoke command (Windows PowerShell, project root):

```powershell
javac --release 17 -encoding UTF-8 -cp 'contracts/target/classes;application/target/classes;desktop/target/classes' -d verification/generated verification/NativeWindowSmoke.java
java -cp 'contracts/target/classes;application/target/classes;desktop/target/classes;verification/generated' NativeWindowSmoke
```

No authentication, API, Supabase/RLS, database transactions, financial operations, email, payment, performance under large datasets, installer, Linux or macOS acceptance has run. No provider behavior is simulated. The preview has three fictional vehicles, not a large fleet test. Malayalam input validation passes; glyph rendering on target devices remains unverified.

The server entry point was executed and returned exit code **2**, as required for its explicitly unavailable state. Baseline/delivery registers contain the same 90 IDs. Final Git status has local untracked project files on main with no commits or remote. `git diff --check` returned no errors but does not validate untracked file contents. The generated offline build/harness transcript is retained as `docs/evidence/foundation-checks.txt`.

## Gate and next work

G1 visual review and manual native keyboard/scaling checks are due under the user's foundational sections 6 and 10 and development procedure sections 3, 5 and 6. SETUP-01 gives exact steps and safe reply. Dependent Stage 2 is paused at this gate. After review, fix reported defects and obtain due dependency/configuration prerequisites before real integration. No operational feature is considered complete from this foundation.
