# Smart VRMS release manifest

Version: **0.2.1-preview**. Classification: **authenticated rental-management beta preview**.
Status: **PREVIEW PUBLICATION AUTHORIZED; PRODUCTION BLOCKED**. This is a beta preview; production readiness is not established. Operator acceptance confirmed hosted staff sign-in and fleet reading, and migration 009 indexes; authenticated live write journeys and full lifecycle acceptance remain pending.

| Artifact | SHA-256 | Bytes |
|---|---|---|
| SmartVRMS-Windows-x64.zip | 8C100FC78800F3B33D37DB2B86D4A7B4AA3A59164D3D807D21705C1E145D9834 | 32164834 |
| SmartVRMS.exe | 02088A27388F77F5D69D3D2E79D10E1985872471F173D8CE495FE8C844F40BEF | Native Windows launcher |
| server.jar | B51A3E35C58941618C2A329C5FA4DB93182B9F4C4E50D7A15106CF30C753DA44 | Server build input; excluded from the desktop package |

Only the client ZIP and SHA256SUMS.txt are intended as public preview assets. The server JAR is listed to identify local verification inputs; it is not bundled in the client.
These hashes identify the build that generated this file. CI rebuilds publish their own SHA256SUMS.txt and Release-Manifest.md assets; use those release assets to verify the downloaded ZIP, not a different local build's hash.

| Platform | Evidence |
|---|---|
| Windows 11 x64 | App-image build, native keyboard smoke and launcher process test; unsigned |
| macOS Intel / Apple Silicon | PENDING RUNNER — no native device acceptance |
| Ubuntu 24.04 GNOME x64 | PENDING RUNNER — no native device acceptance |

The desktop bundle contains the public API endpoint only. User credentials and session tokens are never packaged. The client contains the current rental workflow implementation, but live write journeys beyond staff authentication and fleet reads remain unaccepted. See [Release-Audit.md](Release-Audit.md).
The stable latest-release URL will not resolve until a stable release exists. Preview downloads use their versioned release tags. Already downloaded executables are replaced by downloading a later build.