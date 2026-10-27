package in.smartvrms.server.finance;

import in.smartvrms.contracts.BillingDocumentRecord;
import in.smartvrms.contracts.BillingItemRecord;
import in.smartvrms.contracts.PaymentRecord;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class PdfDocumentGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
        .ofPattern("dd MMM yyyy HH:mm z")
        .withZone(ZoneId.of("Asia/Kolkata"));

    private PdfDocumentGenerator() {}

    public static byte[] generateBillingDocumentPdf(
        BillingDocumentRecord document,
        String customerName,
        String customerContact,
        List<BillingItemRecord> items,
        List<PaymentRecord> payments
    ) {
        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);

            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            float pageWidth = page.getMediaBox().getWidth();
            float margin = 50f;
            float y = 792f; // Top of A4

            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                // Header Banner
                cs.beginText();
                cs.setFont(fontBold, 18);
                cs.newLineAtOffset(margin, y);
                cs.showText("SMART VRMS — COMMERCIAL RENTAL SERVICES");
                cs.endText();

                y -= 22;
                cs.beginText();
                cs.setFont(fontBold, 12);
                cs.newLineAtOffset(margin, y);
                cs.showText("DOCUMENT: " + document.documentType() + " (" + document.documentNumber() + " v" + document.documentVersion() + ")");
                cs.endText();

                y -= 16;
                cs.beginText();
                cs.setFont(fontOblique, 10);
                cs.newLineAtOffset(margin, y);
                cs.showText("[BETA PRO-FORMA / NOT AN OFFICIAL GST TAX INVOICE - VERIFIED REGISTRATION PENDING]");
                cs.endText();

                y -= 8;
                cs.setLineWidth(1f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                // Metadata Section
                y -= 20;
                cs.beginText();
                cs.setFont(fontBold, 10);
                cs.newLineAtOffset(margin, y);
                cs.showText("Customer: ");
                cs.setFont(fontRegular, 10);
                cs.showText(customerName != null ? customerName : "N/A");
                cs.endText();

                cs.beginText();
                cs.setFont(fontBold, 10);
                cs.newLineAtOffset(320, y);
                cs.showText("Date Issued: ");
                cs.setFont(fontRegular, 10);
                cs.showText(document.issuedAt() != null ? DATE_FORMATTER.format(document.issuedAt()) : "N/A");
                cs.endText();

                y -= 15;
                cs.beginText();
                cs.setFont(fontBold, 10);
                cs.newLineAtOffset(margin, y);
                cs.showText("Contact: ");
                cs.setFont(fontRegular, 10);
                cs.showText(customerContact != null ? customerContact : "N/A");
                cs.endText();

                cs.beginText();
                cs.setFont(fontBold, 10);
                cs.newLineAtOffset(320, y);
                cs.showText("Booking Ref: ");
                cs.setFont(fontRegular, 10);
                cs.showText(document.bookingId() != null ? document.bookingId().toString().substring(0, 8) : "N/A");
                cs.endText();

                // Items Table Header
                y -= 25;
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 14;
                cs.beginText();
                cs.setFont(fontBold, 10);
                cs.newLineAtOffset(margin, y);
                cs.showText("Item Description");
                cs.newLineAtOffset(280, 0);
                cs.showText("Qty");
                cs.newLineAtOffset(50, 0);
                cs.showText("Rate (INR)");
                cs.newLineAtOffset(80, 0);
                cs.showText("Total (INR)");
                cs.endText();

                y -= 6;
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                // Items Rows
                if (items != null && !items.isEmpty()) {
                    for (BillingItemRecord item : items) {
                        y -= 16;
                        cs.beginText();
                        cs.setFont(fontRegular, 9);
                        cs.newLineAtOffset(margin, y);
                        String desc = item.description();
                        if (desc.length() > 45) {
                            desc = desc.substring(0, 42) + "...";
                        }
                        cs.showText(desc);
                        cs.newLineAtOffset(280, 0);
                        cs.showText(String.valueOf(item.quantity()));
                        cs.newLineAtOffset(50, 0);
                        cs.showText(formatPaise(item.unitRatePaise()));
                        cs.newLineAtOffset(80, 0);
                        cs.showText(formatPaise(item.totalPaise()));
                        cs.endText();
                    }
                } else {
                    y -= 16;
                    cs.beginText();
                    cs.setFont(fontRegular, 9);
                    cs.newLineAtOffset(margin, y);
                    cs.showText("Standard Rental Package");
                    cs.newLineAtOffset(280, 0);
                    cs.showText("1");
                    cs.newLineAtOffset(50, 0);
                    cs.showText(formatPaise(document.totalPaise()));
                    cs.newLineAtOffset(80, 0);
                    cs.showText(formatPaise(document.totalPaise()));
                    cs.endText();
                }

                // Financial Summary Box
                y -= 20;
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 16;
                float summaryX = 300f;
                drawSummaryRow(cs, fontBold, fontRegular, summaryX, y, "Total Billed Amount:", formatPaise(document.totalPaise()));
                y -= 14;
                drawSummaryRow(cs, fontBold, fontRegular, summaryX, y, "Advance Paid:", formatPaise(document.advancePaidPaise()));
                y -= 14;
                drawSummaryRow(cs, fontBold, fontRegular, summaryX, y, "Net Balance Due:", formatPaise(document.balanceDuePaise()));
                y -= 14;
                drawSummaryRow(cs, fontBold, fontRegular, summaryX, y, "Security Deposit Held:", formatPaise(document.depositHeldPaise()));
                y -= 14;
                drawSummaryRow(cs, fontBold, fontRegular, summaryX, y, "GST Tax Amount:", "INR 0.00 (Unverified Beta)");

                // Payment History Section
                y -= 30;
                cs.beginText();
                cs.setFont(fontBold, 11);
                cs.newLineAtOffset(margin, y);
                cs.showText("Verified Receipts & Allocations");
                cs.endText();

                y -= 6;
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                if (payments != null && !payments.isEmpty()) {
                    y -= 14;
                    cs.beginText();
                    cs.setFont(fontBold, 9);
                    cs.newLineAtOffset(margin, y);
                    cs.showText("Date");
                    cs.newLineAtOffset(120, 0);
                    cs.showText("Method");
                    cs.newLineAtOffset(90, 0);
                    cs.showText("Reference / UTR");
                    cs.newLineAtOffset(150, 0);
                    cs.showText("Amount (INR)");
                    cs.endText();

                    for (PaymentRecord p : payments) {
                        y -= 14;
                        cs.beginText();
                        cs.setFont(fontRegular, 8);
                        cs.newLineAtOffset(margin, y);
                        cs.showText(p.verifiedAt() != null ? DATE_FORMATTER.format(p.verifiedAt()).substring(0, 17) : "N/A");
                        cs.newLineAtOffset(120, 0);
                        cs.showText(p.paymentMethod());
                        cs.newLineAtOffset(90, 0);
                        String ref = p.externalReference() != null ? p.externalReference() : "INTERNAL";
                        if (ref.length() > 25) {
                            ref = ref.substring(0, 22) + "...";
                        }
                        cs.showText(ref);
                        cs.newLineAtOffset(150, 0);
                        cs.showText(formatPaise(p.amountPaise()));
                        cs.endText();
                    }
                } else {
                    y -= 14;
                    cs.beginText();
                    cs.setFont(fontRegular, 8);
                    cs.newLineAtOffset(margin, y);
                    cs.showText("No payments recorded on this document.");
                    cs.endText();
                }

                // Footer
                cs.beginText();
                cs.setFont(fontRegular, 8);
                cs.newLineAtOffset(margin, 35);
                cs.showText("Smart VRMS Enterprise — Immutable Financial Record. Generated at " + DATE_FORMATTER.format(java.time.Instant.now()));
                cs.newLineAtOffset(pageWidth - (margin * 2) - 60, 0);
                cs.showText("Page 1 of 1");
                cs.endText();
            }

            pdf.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate PDF document: " + e.getMessage(), e);
        }
    }

    private static void drawSummaryRow(PDPageContentStream cs, PDType1Font fontBold, PDType1Font fontRegular, float x, float y, String label, String value) throws IOException {
        cs.beginText();
        cs.setFont(fontBold, 9);
        cs.newLineAtOffset(x, y);
        cs.showText(label);
        cs.setFont(fontRegular, 9);
        cs.newLineAtOffset(130, 0);
        cs.showText(value);
        cs.endText();
    }

    private static String formatPaise(long paise) {
        boolean neg = paise < 0;
        long abs = Math.abs(paise);
        long rupees = abs / 100;
        long remainder = abs % 100;
        String formatted = String.format(java.util.Locale.US, "INR %s%d.%02d", (neg ? "-" : ""), rupees, remainder);
        return formatted;
    }
}
