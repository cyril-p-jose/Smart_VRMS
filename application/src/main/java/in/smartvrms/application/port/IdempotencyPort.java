package in.smartvrms.application.port;

import in.smartvrms.contracts.IdempotencyRecord;
import in.smartvrms.contracts.StaffSession;

import java.util.Optional;

public interface IdempotencyPort {
    Optional<IdempotencyRecord> check(StaffSession session, String idempotencyKey, String operation, String requestHash);
    IdempotencyRecord recordPending(StaffSession session, String idempotencyKey, String operation, String requestHash, int ttlSeconds);
    void recordCompletion(StaffSession session, String idempotencyKey, int responseStatus, String responseBody);
    void recordFailure(StaffSession session, String idempotencyKey, int responseStatus, String responseBody);
}
