package in.smartvrms.server.automation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure deterministic scanner evaluating document expiry reminders (FR-DOC-003, FR-DOC-004).
 * Enforces Asia/Kolkata time boundaries and strict 72-hour window constraints.
 */
public final class DocumentReminderScanner {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final long SEVENTY_TWO_HOURS_SECONDS = 72L * 3600L;

    public enum ReminderStage {
        NONE,
        LESS_THAN_72H,
        EXPIRED
    }

    private DocumentReminderScanner() { }

    /**
     * Evaluates document expiry against current clock time.
     * - Exactly 72h remaining is EXCLUDED.
     * - 0 < remaining < 72h (e.g. 71h59m) is LESS_THAN_72H.
     * - remaining <= 0 is EXPIRED.
     * - Non-expiring document (expiryTime == null) returns NONE.
     */
    public static ReminderStage evaluateStage(Instant expiryTime, Instant clockTime) {
        if (expiryTime == null) {
            return ReminderStage.NONE;
        }
        Objects.requireNonNull(clockTime, "clockTime");

        Duration remaining = Duration.between(clockTime, expiryTime);
        if (remaining.isNegative() || remaining.isZero()) {
            return ReminderStage.EXPIRED;
        }

        long seconds = remaining.getSeconds();
        if (seconds < SEVENTY_TWO_HOURS_SECONDS) {
            return ReminderStage.LESS_THAN_72H;
        }

        return ReminderStage.NONE;
    }

    /**
     * Formats stable event idempotency key per version-stage-recipient.
     */
    public static String buildIdempotencyKey(UUID companyId, UUID documentId, int version, ReminderStage stage, String recipientEmail) {
        return "REMINDER-" + companyId + "-" + documentId + "-v" + version + "-" + stage.name() + "-" + recipientEmail.toLowerCase().trim();
    }
}
