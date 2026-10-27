package in.smartvrms.contracts;

import java.util.UUID;

public record QrIntentRecord(
    UUID requestId,
    String upiUri,
    String merchantVpa,
    String merchantName,
    long amountPaise,
    String base64PngQr
) {}
