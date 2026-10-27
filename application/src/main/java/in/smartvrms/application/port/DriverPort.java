package in.smartvrms.application.port;

import in.smartvrms.contracts.DriverProfileRecord;
import in.smartvrms.contracts.StaffSession;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DriverPort {
    DriverProfileRecord registerDriverProfile(StaffSession session, UUID employeeId, String licenceNumber, List<String> classes, LocalDate expiry, int experienceYears);
    List<DriverProfileRecord> listDrivers(StaffSession session, String dutyStatus);
    void updateDutyStatus(StaffSession session, UUID driverId, String dutyStatus);
}
