package in.smartvrms.application.port;

import in.smartvrms.contracts.MemberManagementRecord;
import in.smartvrms.contracts.StaffSession;

import java.util.List;
import java.util.UUID;

public interface StaffProvisioningPort {
    MemberManagementRecord inviteEmployee(StaffSession session, String email, String fullName);
    MemberManagementRecord revokeMembership(StaffSession session, UUID targetAuthUserId, String reason);
    void handoverPrimaryAdministrator(StaffSession session, UUID newPrimaryAdminUserId);
    List<MemberManagementRecord> listMembers(StaffSession session);
}
