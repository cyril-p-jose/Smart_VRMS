package in.smartvrms.server.security;

import java.util.UUID;

/** Tests the Java policy only; does not assert Supabase authorization or RLS behavior. */
public final class SecurityPolicyChecks {
    private static int checks;
    private static void denied(Runnable operation) {
        try { operation.run(); throw new AssertionError("Expected access denial"); }
        catch(SecurityException expected) { if(!expected.getMessage().equals("Access denied.")) throw new AssertionError("Unsafe denial message"); checks++; }
    }
    public static void main(String[] args) {
        UUID company=UUID.randomUUID(),other=UUID.randomUUID(),user=UUID.randomUUID();
        var admin=new MembershipSnapshot(user,company,MembershipSnapshot.Role.ADMINISTRATOR,true);
        var employee=new MembershipSnapshot(user,company,MembershipSnapshot.Role.EMPLOYEE,true);
        var inactive=new MembershipSnapshot(user,company,MembershipSnapshot.Role.ADMINISTRATOR,false);
        for(Capability action:Capability.values()) {
            AccessPolicy.require(admin,company,action);checks++;
            denied(()->AccessPolicy.require(null,company,action));
            denied(()->AccessPolicy.require(inactive,company,action));
            denied(()->AccessPolicy.require(admin,other,action));
            denied(()->AccessPolicy.require(employee,other,action));
        }
        // Explicit approved-role expectations, independent of enum metadata.
        for(Capability action:new Capability[]{Capability.VIEW_COMPENSATION,Capability.MANAGE_PERSONNEL,Capability.VIEW_COMPANY_MARGIN,
                Capability.POST_REFUND,Capability.POST_DISCOUNT,Capability.CORRECT_FINANCIAL_ENTRY,Capability.INVITE_EMPLOYEE,
                Capability.EXPORT_SENSITIVE_REPORT,Capability.CONFIGURE_BUSINESS,Capability.MERGE_CUSTOMER_IDENTITY}) {
            denied(()->AccessPolicy.require(employee,company,action));
        }
        for(Capability action:new Capability[]{Capability.RECORD_VERIFIED_PAYMENT,Capability.BOOK_STANDARD_RENTAL,
                Capability.RECORD_RETURN_INSPECTION,Capability.FINALIZE_STANDARD_INVOICE,Capability.RECORD_REVIEW}) {
            AccessPolicy.require(employee,company,action);checks++;
        }
        denied(()->AccessPolicy.require(admin,company,null));denied(()->AccessPolicy.require(admin,null,Capability.VIEW_FLEET));
        System.out.println("PASS: "+checks+" server permission assertions. Live identity, database/RLS and Storage NOT verified.");
    }
}
