package in.smartvrms.application.port;

import in.smartvrms.contracts.AttendanceRecord;
import in.smartvrms.contracts.CompensationRecord;
import in.smartvrms.contracts.EmployeeRecord;
import in.smartvrms.contracts.StaffSession;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonnelPort {
    EmployeeRecord registerEmployee(StaffSession session, String fullName, String contact, String email, String address, LocalDate joiningDate, boolean isDriver, UUID photoFileId);
    EmployeeRecord updateEmployee(StaffSession session, UUID employeeId, String fullName, String contact, String email, String address, String employmentStatus);
    EmployeeRecord deactivateEmployee(StaffSession session, UUID employeeId, String reason);
    List<EmployeeRecord> listEmployees(StaffSession session, boolean activeOnly);
    Optional<EmployeeRecord> findDuplicateEmployee(StaffSession session, String contactNumber, String email);
    AttendanceRecord recordAttendance(StaffSession session, UUID employeeId, LocalDate date, String status, String notes);
    List<AttendanceRecord> getAttendanceHistory(StaffSession session, UUID employeeId, LocalDate from, LocalDate to);
    CompensationRecord recordCompensation(StaffSession session, UUID employeeId, String arrangementType, long baseRatePaise, long tripAllowancePaise, LocalDate effectiveFrom);
    Optional<CompensationRecord> getCompensation(StaffSession session, UUID employeeId);
}
