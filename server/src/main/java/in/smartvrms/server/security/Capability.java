package in.smartvrms.server.security;

/** Role-level policy only. Use cases must additionally enforce resource ownership and invariants. */
public enum Capability {
    VIEW_FLEET(false), RECORD_FLEET_FREEZE(false), RENEW_ASSIGNED_DOCUMENT(false),
    VIEW_DRIVER_OPERATIONS(false), REQUEST_DRIVER_UNAVAILABILITY(false),
    UPDATE_CUSTOMER_OPERATIONS(false), VIEW_CUSTOMER_RECORDS(false), READ_RENTAL_DOCUMENT(false),
    BOOK_STANDARD_RENTAL(false), CHECKOUT(false), RECORD_RETURN_INSPECTION(false),
    EXTEND_AT_SAVED_RATES(false), CANCEL_STANDARD_BEFORE_CHECKOUT(false),
    PREPARE_RENTAL_DOCUMENT(false), FINALIZE_STANDARD_INVOICE(false), RECORD_VERIFIED_PAYMENT(false),
    REQUEST_FINANCIAL_CORRECTION(false), RECORD_MAINTENANCE_EVIDENCE(false),
    VIEW_OPERATIONAL_BALANCE(false), RECORD_REVIEW(false), REQUEST_BOOKING_EMAIL_RESEND(false),
    VIEW_OPERATIONAL_HISTORY(false), ENQUEUE_CUSTOMER_MESSAGE(false), CORRECT_SERVICE_REVIEW(true),
    MANAGE_VEHICLE_MASTER(true), MANAGE_RATES(true), MANAGE_DRIVER_MASTER(true),
    APPROVE_DRIVER_LEAVE(true), MERGE_CUSTOMER_IDENTITY(true), ADJUST_PRICE(true),
    COMPLEX_CANCELLATION(true), POST_DISCOUNT(true), POST_REFUND(true), POST_CREDIT_NOTE(true),
    CORRECT_FINANCIAL_ENTRY(true), APPROVE_MAINTENANCE_RESOLUTION(true),
    MANAGE_PERSONNEL(true), VIEW_COMPENSATION(true), MANAGE_ATTENDANCE(true),
    MANAGE_EXPENSES(true), VIEW_COMPANY_MARGIN(true), EXPORT_SENSITIVE_REPORT(true),
    MANAGE_NOTIFICATIONS(true), VIEW_BUSINESS_AUDIT(true), CONFIGURE_BUSINESS(true),
    INVITE_EMPLOYEE(true), REVOKE_MEMBERSHIP(true);

    private final boolean administratorOnly;
    Capability(boolean administratorOnly) { this.administratorOnly=administratorOnly; }
    public boolean administratorOnly() { return administratorOnly; }
}
