# SETUP-16 — Hosted desktop staff acceptance

Purpose: confirm the Windows desktop candidate reaches the public Java API, accepts a real invited staff login, and reads the authenticated company's fleet. This is a read-only acceptance check; do not create or edit business records during this step.

Prerequisites: SETUP-14 previously passed; Render service is Live; the provided `0.2.0-preview` Windows candidate ZIP is available; an invited administrator or employee account is active. Never send the staff password, database password, access token, or customer data in chat or screenshots.

## Manglish steps

1. Candidate ZIP download cheythu oru puthiya folder-il extract cheyyuka. ZIP direct-aayi open cheythu app run cheyyaruthu; full archive extract cheyyanam.
2. Extracted folder-il `SmartVRMS/SmartVRMS.exe` open cheyyuka. App first launch-il Render free service asleep aanel wake aavan 1 minute vare edukkaam. Once wait cheythu sign-in retry cheyyuka.
3. **Sign in** screen-il invited staff emailum passwordum app-il mathram type cheyyuka. Iva chat-il ayakkaruthu. Supabase database password/publishable key ivide idaruthu.
4. Sign in kazhinju **Vehicles / Fleet** page open cheyyuka. Existing company categoriesum vehiclesum load aakunnundo verify cheyyuka. Employee account-il register buttons unavailable/read-only aayirikkam; athu expected role behavior aanu.
5. Ippo vehicle/category create cheyyenda; ee acceptance step read-only aanu. Screenshot share cheyyunnenkil customer details, registration plates, emails, or other business data hide cheyyuka.

## Expected result

- Sign-in succeeds.
- Fleet screen reports loaded data or a clear empty-company state; it does not show fictional sample vehicles as live fleet.
- No credentials, tokens, or private database settings are requested by the desktop app.

## If it fails

- First request wake-up timeout: 60 seconds wait cheythu once retry cheyyuka.
- Invalid credentials: Supabase Auth-il aa invited staff account active aano administrator privately confirm cheyyanam. Password ivide share cheyyaruthu.
- Sign-in works but fleet request forbidden: company membership active aano; role/capability issue aanu.
- API/server/network error: app versionum sanitized error messageum mathram share cheyyuka. Token/password fields ullathaya log/screenshot share cheyyaruthu.
- Blank or fictional rows after sign-in: app version and fleet page state report cheyyuka; business write cheyyaruthu.

## Safe reply

```text
DONE SETUP-16
App version: 0.2.0-preview
Staff sign-in: PASS / FAIL
Fleet screen: loaded rows / empty company / sanitized error
```
