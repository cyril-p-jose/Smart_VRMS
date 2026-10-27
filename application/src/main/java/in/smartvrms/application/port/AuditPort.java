package in.smartvrms.application.port;

import in.smartvrms.contracts.AuditEventRecord;
import in.smartvrms.contracts.StaffSession;

import java.util.List;

public interface AuditPort {
    AuditEventRecord recordEvent(StaffSession session, String action, String resourceType, String resourceId, String detailsJson);
    List<AuditEventRecord> queryEvents(StaffSession session, String resourceType, String resourceId, int limit);
}
