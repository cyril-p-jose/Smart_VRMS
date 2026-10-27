# Demo workspace data

Smart VRMS opens with a signed-out interface preview. Its examples are fictional local presentation data, not customer records and not seeded into Supabase. Selecting **Explore interface preview** lets a presenter browse these screens without a staff login. The app never sends or saves this data.

## Included examples

The preview contains 10 vehicles, 10 customer profiles, 10 driver profiles, 10 personnel rows, 10 active-rental examples, 10 sample payment rows, 10 sample invoice rows, and 10 sample reviews. Names include Mathew, Adwaith, Cyril, Rohan, and Arun as requested.

All phone numbers are masked (`+91 9XXXX ...`); vehicle IDs use the clearly invalid `DEMO-KL-...` pattern. Sample payment references begin `DEMO-PAY-`; sample invoice/booking/review identifiers begin `DEMO-`. No real license numbers, bank UTRs, email addresses, or home addresses are included.

## Scenarios shown

- Fleet examples span compact cars, sedans, SUVs, an MPV, an electric car, motorcycles, and a scooter.
- Driver profiles show a mix of licence classes and illustrative available, on-duty, and leave statuses.
- Running-rental examples include 10 active entries, 3 overdue examples, optional sample driver assignments, and balances that reconcile with the illustrative finance totals.
- Finance shows INR 97,800 billed, INR 72,100 in sample receipts, INR 25,700 outstanding, and INR 10,000 in illustrative deposits. Every row is marked as a sample; no payment was verified, invoice issued, or ledger entry posted.
- Ten review examples have an average score of 4.5/5. They are not real customer submissions.
- The email outbox remains empty and marked **NO SENDER**. The demo does not pretend that reminders or emails were delivered.

After staff sign-in, connected screens load the authenticated company's records instead of these preview examples. Use the preview for presentation and training only; it is not an import or database seeding tool.
