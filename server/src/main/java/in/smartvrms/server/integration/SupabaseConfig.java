package in.smartvrms.server.integration;

import java.net.URI;
import java.util.Properties;

/** Public configuration only. Secret/service-role credentials are never accepted by this adapter. */
public final class SupabaseConfig {
    private final URI endpoint;
    private final String publishableKey;
    public SupabaseConfig(String url,String key) {
        try {
            URI parsed=URI.create(url==null?"":url.strip());
            if(!"https".equals(parsed.getScheme())||parsed.getHost()==null
                    ||!parsed.getHost().matches("[a-z0-9]{20}\\.supabase\\.co")
                    ||parsed.getPort()!=-1||parsed.getUserInfo()!=null||parsed.getQuery()!=null||parsed.getFragment()!=null
                    ||!(parsed.getPath().isEmpty()||parsed.getPath().equals("/"))) throw new IllegalArgumentException();
            if(key==null||!key.matches("sb_publishable_[A-Za-z0-9_-]{10,200}")) throw new IllegalArgumentException();
            endpoint=URI.create("https://"+parsed.getHost()); publishableKey=key;
        } catch(IllegalArgumentException ex) { throw new IllegalArgumentException("Use a Supabase project HTTPS URL and a publishable key starting sb_publishable_."); }
    }
    public static SupabaseConfig from(Properties properties) {
        return new SupabaseConfig(properties.getProperty("supabase.url"),properties.getProperty("supabase.publishableKey"));
    }
    public URI endpoint() { return endpoint; }
    public String publishableKey() { return publishableKey; }
    @Override public String toString() { return "SupabaseConfig[values omitted]"; }
}
