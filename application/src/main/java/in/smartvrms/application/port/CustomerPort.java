package in.smartvrms.application.port;

import in.smartvrms.contracts.CustomerRecord;
import in.smartvrms.contracts.CustomerVisitRecord;
import in.smartvrms.contracts.StaffSession;

import java.util.List;
import java.util.UUID;

public interface CustomerPort {
    CustomerRecord createCustomerWithVisit(StaffSession session, String fullName, String mobile, String email, String address, int ageAtVisit, String bookingContext);
    List<CustomerRecord> searchCustomers(StaffSession session, String query);
    CustomerVisitRecord recordRepeatVisit(StaffSession session, UUID customerId, int ageAtVisit, String bookingContext);
    List<CustomerVisitRecord> getVisitHistory(StaffSession session, UUID customerId);
    default java.util.Optional<CustomerRecord> findDuplicateCustomer(StaffSession session, String mobile, String email) {
        return java.util.Optional.empty();
    }
}
