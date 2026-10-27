# Smart VRMS 0.2.1-preview

**Authenticated rental-management beta preview. This is not a production-certified or bug-free system.**

Download `SmartVRMS-Windows-x64.zip`, extract the full archive, and open `SmartVRMS/SmartVRMS.exe`. Windows x64 package; Java is bundled. The binary is unsigned. Use the `SHA256SUMS.txt` and `Release-Manifest.md` attached to this exact release.

The current source includes the implemented rental-management modules and passes the repository's local fixture/regression verification suite. Operator acceptance on the hosted development system covers staff sign-in and fleet reads, plus Supabase migration 009 and its six indexes. Authenticated live writes and complete end-to-end business journeys have not been independently accepted against the hosted database. Validate with authorized staff and approved test records before using real rentals, financial records, or customer data.

The application uses the public API at `https://smart-vrms.onrender.com` by default. Staff accounts and company membership must already be provisioned by an administrator; downloading the client does not grant access. The prototype uses manual UPI/payment recording, not automatic payment confirmation. Statutory payroll, outbound email delivery, and verified backup/restore are not claimed as operational. Free hosting can sleep or become unavailable.

The signed-out **Explore interface preview** includes coherent fictional examples across fleet, customers, drivers, personnel, running rentals, finance, and reviews. Sample rows are clearly labeled and never sent to the API or saved in Supabase. Masked contact data and `DEMO-` identifiers are used. The outbox remains empty because email delivery is not configured. See the [demo data catalog](https://github.com/cyril-p-jose/Smart_VRMS/blob/main/docs/Demo-Data-Catalog.md).

macOS and Linux packages are not supplied. This Windows package is unsigned. Already downloaded copies do not update automatically; Check for updates opens the release page and the user downloads the new version.
