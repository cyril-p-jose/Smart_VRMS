package in.smartvrms.application.port;

import in.smartvrms.contracts.*;

import java.util.List;
import java.util.UUID;

/** Application port for running services, custody checkout/return, extensions, and maintenance (FR-OPS-001..004, FR-EXT-001..003, FR-DMG-001..004). */
public interface OperationsPort {

    CustodyRecord recordCheckout(StaffSession session, CheckoutCommand cmd);

    CustodyRecord recordReturnInspection(StaffSession session, ReturnInspectionCommand cmd);

    ExtensionQuote quoteExtension(UUID companyId, ExtensionCommand cmd);

    BookingRecord extendAssignments(StaffSession session, ExtensionCommand cmd);

    List<RunningServiceSummary> listRunningServices(UUID companyId, boolean overdueOnly);

    RunningServiceSummary getRunningServiceDetail(UUID companyId, UUID bookingId);

    MaintenanceCaseRecord openMaintenanceCase(
            StaffSession session,
            UUID vehicleId,
            UUID assignmentId,
            UUID custodyId,
            String issueType,
            long estimatedCostPaise,
            long allegedRecoveryPaise,
            String notes,
            UUID photoFileId
    );

    MaintenanceCaseRecord resolveMaintenanceCase(StaffSession session, MaintenanceResolutionCommand cmd);

    List<MaintenanceCaseRecord> listMaintenanceCases(UUID companyId, UUID vehicleId, boolean openOnly);
}
