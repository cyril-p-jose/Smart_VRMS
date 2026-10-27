package in.smartvrms.server.security;

import java.util.UUID;

/** Default deny. No API is listening and no caller can manufacture a network session from this policy. */
final class AccessPolicy {
    private AccessPolicy() { }
    static void require(MembershipSnapshot membership,UUID resourceCompany,Capability capability) {
        if(membership==null||resourceCompany==null||capability==null||!membership.active()
                ||!membership.companyId().equals(resourceCompany)
                ||(capability.administratorOnly()&&membership.role()!=MembershipSnapshot.Role.ADMINISTRATOR)) {
            // Do not disclose the resource, caller identity, tenant existence, or financial details.
            throw new SecurityException("Access denied.");
        }
    }
}
