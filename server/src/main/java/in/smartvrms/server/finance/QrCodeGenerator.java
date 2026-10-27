package in.smartvrms.server.finance;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class QrCodeGenerator {

    private QrCodeGenerator() {}

    public static byte[] generateQrPng(String payload, int width, int height) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("QR payload cannot be empty");
        }
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(payload, BarcodeFormat.QR_CODE, width, height);
            ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
            return pngOutputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate QR code: " + e.getMessage(), e);
        }
    }

    public static String generateQrBase64Png(String payload, int width, int height) {
        byte[] pngBytes = generateQrPng(payload, width, height);
        return Base64.getEncoder().encodeToString(pngBytes);
    }

    public static String buildUpiUri(String vpa, String payeeName, long amountPaise, String transactionNote, String referenceId) {
        if (vpa == null || vpa.isBlank()) {
            throw new IllegalArgumentException("VPA cannot be blank");
        }
        double amountRupees = amountPaise / 100.0;
        String formattedAmount = String.format(java.util.Locale.US, "%.2f", amountRupees);
        StringBuilder sb = new StringBuilder("upi://pay?");
        sb.append("pa=").append(urlEncode(vpa.trim()));
        if (payeeName != null && !payeeName.isBlank()) {
            sb.append("&pn=").append(urlEncode(payeeName.trim()));
        }
        sb.append("&am=").append(formattedAmount);
        sb.append("&cu=INR");
        if (transactionNote != null && !transactionNote.isBlank()) {
            sb.append("&tn=").append(urlEncode(transactionNote.trim()));
        }
        if (referenceId != null && !referenceId.isBlank()) {
            sb.append("&tr=").append(urlEncode(referenceId.trim()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
