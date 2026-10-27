package in.smartvrms.server.api;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Map;
import javax.net.ssl.SSLContext;

/** Configuration for the local JDK HTTP/HTTPS server. Bound strictly to 127.0.0.1. */
public final class HttpServerConfig {
    private final InetSocketAddress bindAddress;
    private final SSLContext sslContext;

    public HttpServerConfig(int port) {
        this(new InetSocketAddress("127.0.0.1", port), null);
    }

    /** Public HTTPS is terminated by the explicitly configured trusted hosting proxy. */
    public static HttpServerConfig fromEnvironment(Map<String,String> environment) {
        String raw=environment.getOrDefault("SMART_VRMS_PORT",environment.getOrDefault("PORT","8080"));
        final int port;
        try { port=Integer.parseInt(raw); } catch(RuntimeException ex) { throw new IllegalArgumentException("Invalid API port."); }
        if(port<1 || port>65535) throw new IllegalArgumentException("Invalid API port.");
        boolean proxy="true".equalsIgnoreCase(environment.get("SMART_VRMS_BEHIND_TLS_PROXY"));
        return new HttpServerConfig(new InetSocketAddress(proxy?"0.0.0.0":"127.0.0.1",port),null);
    }

    public HttpServerConfig(InetSocketAddress bindAddress, SSLContext sslContext) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.sslContext = sslContext;
    }

    public InetSocketAddress bindAddress() { return bindAddress; }
    public SSLContext sslContext() { return sslContext; }
    public boolean isTls() { return sslContext != null; }
}
