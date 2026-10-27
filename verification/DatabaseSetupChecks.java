import in.smartvrms.server.setup.DatabaseReadinessMain;

public final class DatabaseSetupChecks {
    public static void main(String[] args) {
        String[] rejected = {null, "", "localhost", "aws-0-ap-south-1.pooler.supabase.com", "aws-0-ap-northeast-1.pooler.supabase.com.evil.test", "aws-0-ap-northeast-1.pooler.supabase.com:5432", "aws-0-ap-northeast-1.pooler.supabase.com?sslmode=disable", "postgres://user:password@aws-0-ap-northeast-1.pooler.supabase.com", "aws-0-ap-northeast-1.pooler.supabase.com\n"};
        for (String value : rejected) if (DatabaseReadinessMain.validHost(value)) throw new AssertionError("Unsafe host accepted");
        if (!DatabaseReadinessMain.validHost("aws-0-ap-northeast-1.pooler.supabase.com")) throw new AssertionError("Valid host rejected");
        System.out.println("Database setup input checks: 10 passed (no database connection attempted).");
    }
}
