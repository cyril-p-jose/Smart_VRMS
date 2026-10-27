package in.smartvrms.desktop.ui;

import java.util.List;

/**
 * Coherent, fictional presentation data for the signed-out interface preview.
 * Nothing in this class is sent to the API or persisted. Contact details and
 * registration IDs are intentionally masked/non-valid to avoid impersonating
 * real people or vehicles.
 */
final class DemoData {
    private DemoData() { }

    record Vehicle(String name, String category, String specification, String demoRegistration) { }
    record Customer(String name, String maskedPhone, String city, int ageAtVisit, int completedRentals) { }
    record Driver(String name, String licenceClasses, int yearsExperience, String dutyStatus) { }
    record Employee(String name, String status, String role) { }
    record Review(String customer, int rating, String comment, int daysAgo) { }
    record Payment(String method, long amountPaise, String reference, int daysAgo) { }
    record Invoice(String number, String type, long totalPaise, long paidPaise, int daysAgo) { }

    static final List<Vehicle> VEHICLES = List.of(
            new Vehicle("City hatchback", "COMPACT", "5 seats · Petrol · Manual · A/C", "DEMO-KL-001"),
            new Vehicle("Compact sedan", "SEDAN", "5 seats · Petrol · Manual · A/C", "DEMO-KL-002"),
            new Vehicle("Premium sedan", "SEDAN", "5 seats · Diesel · Automatic · A/C", "DEMO-KL-003"),
            new Vehicle("Compact SUV", "SUV", "5 seats · Petrol · Automatic · A/C", "DEMO-KL-004"),
            new Vehicle("Family SUV", "SUV", "5 seats · Diesel · Automatic · A/C", "DEMO-KL-005"),
            new Vehicle("Family MPV", "MPV", "7 seats · Diesel · Manual · A/C", "DEMO-KL-006"),
            new Vehicle("Electric hatchback", "ELECTRIC", "5 seats · Electric · Automatic · A/C", "DEMO-KL-007"),
            new Vehicle("Commuter motorcycle", "MOTORCYCLE", "2 seats · 125 cc · Manual", "DEMO-KL-008"),
            new Vehicle("Touring motorcycle", "MOTORCYCLE", "2 seats · 350 cc · Manual", "DEMO-KL-009"),
            new Vehicle("Urban scooter", "SCOOTER", "2 seats · 125 cc · Automatic", "DEMO-KL-010")
    );

    static final List<Customer> CUSTOMERS = List.of(
            new Customer("Mathew Thomas", "+91 9XXXX 0101", "Kochi", 34, 3),
            new Customer("Adwaith Krishnan", "+91 9XXXX 0102", "Thrissur", 27, 2),
            new Customer("Cyril P Jose", "+91 9XXXX 0103", "Kottayam", 41, 1),
            new Customer("Rohan George", "+91 9XXXX 0104", "Kochi", 29, 4),
            new Customer("Arun Raj", "+91 9XXXX 0105", "Kozhikode", 32, 2),
            new Customer("Anjali Nair", "+91 9XXXX 0106", "Alappuzha", 30, 2),
            new Customer("Nikhil Varghese", "+91 9XXXX 0107", "Ernakulam", 36, 1),
            new Customer("Amina Fathima", "+91 9XXXX 0108", "Malappuram", 25, 3),
            new Customer("Rahul Menon", "+91 9XXXX 0109", "Palakkad", 38, 1),
            new Customer("Devika Suresh", "+91 9XXXX 0110", "Thiruvananthapuram", 28, 2)
    );

    static final List<Driver> DRIVERS = List.of(
            new Driver("Mathew Thomas", "LMV", 8, "AVAILABLE"),
            new Driver("Adwaith Krishnan", "LMV, HMV", 6, "ON_DUTY"),
            new Driver("Cyril P Jose", "LMV", 12, "AVAILABLE"),
            new Driver("Rohan George", "LMV", 5, "ON_DUTY"),
            new Driver("Arun Raj", "LMV, HMV", 9, "AVAILABLE"),
            new Driver("Anjali Nair", "LMV", 4, "ON_LEAVE"),
            new Driver("Nikhil Varghese", "LMV, TRANS", 11, "AVAILABLE"),
            new Driver("Amina Fathima", "LMV", 3, "AVAILABLE"),
            new Driver("Rahul Menon", "LMV, HMV", 7, "ON_DUTY"),
            new Driver("Devika Suresh", "LMV", 5, "AVAILABLE")
    );

    static final List<Employee> EMPLOYEES = List.of(
            new Employee("Mathew Thomas", "Active", "Fleet Operations"),
            new Employee("Adwaith Krishnan", "Active", "Rental Coordinator"),
            new Employee("Cyril P Jose", "Active", "Administrator"),
            new Employee("Rohan George", "Active", "Senior Driver"),
            new Employee("Arun Raj", "Active", "Driver"),
            new Employee("Anjali Nair", "Active", "Customer Desk"),
            new Employee("Nikhil Varghese", "Active", "Fleet Operations"),
            new Employee("Amina Fathima", "On leave", "Rental Coordinator"),
            new Employee("Rahul Menon", "Active", "Driver"),
            new Employee("Devika Suresh", "Active", "Accounts Assistant")
    );

    static final List<Review> REVIEWS = List.of(
            new Review("Mathew Thomas", 5, "Clear pickup instructions and a tidy vehicle.", 2),
            new Review("Adwaith Krishnan", 4, "Booking details were easy to confirm.", 4),
            new Review("Cyril P Jose", 5, "Return inspection was quick and clear.", 6),
            new Review("Rohan George", 4, "Helpful team; vehicle matched the description.", 8),
            new Review("Arun Raj", 5, "Convenient pickup near the station.", 10),
            new Review("Anjali Nair", 4, "Good family vehicle for the trip.", 13),
            new Review("Nikhil Varghese", 5, "Well maintained and ready on time.", 16),
            new Review("Amina Fathima", 4, "The staff explained the rental terms.", 19),
            new Review("Rahul Menon", 5, "Smooth handover and return.", 22),
            new Review("Devika Suresh", 4, "A comfortable drive for our weekend.", 25)
    );

    static final List<Payment> PAYMENTS = List.of(
            new Payment("UPI · sample", 500_000, "DEMO-PAY-0001", 1),
            new Payment("Cash · sample", 800_000, "DEMO-PAY-0002", 2),
            new Payment("UPI · sample", 600_000, "DEMO-PAY-0003", 3),
            new Payment("Bank transfer · sample", 750_000, "DEMO-PAY-0004", 4),
            new Payment("UPI · sample", 1_000_000, "DEMO-PAY-0005", 5),
            new Payment("Cash · sample", 720_000, "DEMO-PAY-0006", 6),
            new Payment("UPI · sample", 800_000, "DEMO-PAY-0007", 7),
            new Payment("Bank transfer · sample", 540_000, "DEMO-PAY-0008", 8),
            new Payment("UPI · sample", 1_000_000, "DEMO-PAY-0009", 9),
            new Payment("Cash · sample", 500_000, "DEMO-PAY-0010", 10)
    );

    static final List<Invoice> INVOICES = List.of(
            new Invoice("DEMO-DOC-0001", "SAMPLE_INVOICE", 850_000, 500_000, 1),
            new Invoice("DEMO-DOC-0002", "SAMPLE_INVOICE", 1_280_000, 800_000, 2),
            new Invoice("DEMO-DOC-0003", "SAMPLE_INVOICE", 600_000, 600_000, 3),
            new Invoice("DEMO-DOC-0004", "SAMPLE_INVOICE", 950_000, 750_000, 4),
            new Invoice("DEMO-DOC-0005", "SAMPLE_INVOICE", 1_500_000, 1_000_000, 5),
            new Invoice("DEMO-DOC-0006", "SAMPLE_INVOICE", 720_000, 720_000, 6),
            new Invoice("DEMO-DOC-0007", "SAMPLE_INVOICE", 1_100_000, 800_000, 7),
            new Invoice("DEMO-DOC-0008", "SAMPLE_INVOICE", 540_000, 540_000, 8),
            new Invoice("DEMO-DOC-0009", "SAMPLE_INVOICE", 1_350_000, 1_000_000, 9),
            new Invoice("DEMO-DOC-0010", "SAMPLE_INVOICE", 890_000, 500_000, 10)
    );

    static long totalBilledPaise() { return INVOICES.stream().mapToLong(Invoice::totalPaise).sum(); }
    static long totalPaidPaise() { return PAYMENTS.stream().mapToLong(Payment::amountPaise).sum(); }
    static long depositsHeldPaise() { return 1_000_000; }
}
