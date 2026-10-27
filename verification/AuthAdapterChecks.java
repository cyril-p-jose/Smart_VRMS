package in.smartvrms.server.integration;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;

/** Protocol fixtures plus real loopback HTTP. This is not live Supabase verification. */
public final class AuthAdapterChecks {
    private static int checks;
    private static final String URL="https://abcdefghijklmnopqrst.supabase.co",KEY="sb_publishable_fixture0123456789";
    private static final String USER="{\"id\":\"12345678-1234-1234-1234-123456789abc\",\"is_anonymous\":false,\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"}";
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneOffset.UTC);
    private static void require(boolean value,String reason) { if(!value)throw new AssertionError(reason);checks++; }
    private static byte[] bytes(String text) {return text.getBytes(StandardCharsets.UTF_8);}
    private static SupabaseAuth auth(int status,String type,String json) {
        return new SupabaseAuth(new SupabaseConfig(URL,KEY),r->new SupabaseAuth.Response(status,type,bytes(json)),CLOCK);
    }
    private static void fails(AuthFailure.Kind kind,Runnable work) {
        try {work.run();throw new AssertionError("Expected "+kind);}catch(AuthFailure error){require(error.kind()==kind,"safe classification");require(!error.getMessage().contains("secret"),"redacted error");}
    }
    public static void main(String[] args) throws Exception {
        require(auth(200,"application/json",USER).verifyStaffIdentity("a.b.c").equals(UUID.fromString("12345678-1234-1234-1234-123456789abc")),"verified provider ID");
        var requestClient=new SupabaseAuth(new SupabaseConfig(URL,KEY),r->{
            require(r.uri().toString().equals(URL+"/auth/v1/user"),"fixed user endpoint");
            require(r.headers().firstValue("Authorization").orElse("").equals("Bearer a.b.c"),"user bearer");
            require(r.headers().firstValue("apikey").orElse("").equals(KEY),"publishable key");
            require(r.timeout().orElseThrow().equals(Duration.ofSeconds(15)),"request deadline");
            return new SupabaseAuth.Response(200,"application/json; charset=utf-8",bytes(USER));
        },CLOCK);requestClient.verifyStaffIdentity("a.b.c");
        var noNetwork=new SupabaseAuth(new SupabaseConfig(URL,KEY),r->{throw new AssertionError("Invalid token made request");},CLOCK);
        for(String token:new String[]{"","bad","secret\r\nInjected: yes","a.b.c ","x".repeat(9000)})fails(AuthFailure.Kind.INVALID_SESSION,()->noNetwork.verifyStaffIdentity(token));
        for(int status:new int[]{401,403})fails(AuthFailure.Kind.INVALID_SESSION,()->auth(status,"application/json","secret").verifyStaffIdentity("a.b.c"));
        fails(AuthFailure.Kind.RATE_LIMITED,()->auth(429,"text/html","secret").verifyStaffIdentity("a.b.c"));
        for(int status:new int[]{302,500,503})fails(AuthFailure.Kind.UNAVAILABLE,()->auth(status,"text/html","secret").verifyStaffIdentity("a.b.c"));
        fails(AuthFailure.Kind.INVALID_RESPONSE,()->auth(200,"text/html",USER).verifyStaffIdentity("a.b.c"));
        fails(AuthFailure.Kind.INVALID_SESSION,()->auth(200,"application/json",USER.replace("false","true")).verifyStaffIdentity("a.b.c"));
        for(String suffix:new String[]{",\"deleted_at\":\"2026-01-01T00:00:00Z\"",",\"banned_until\":\"2027-01-01T00:00:00Z\""})
            fails(AuthFailure.Kind.INVALID_SESSION,()->auth(200,"application/json",USER.substring(0,USER.length()-1)+suffix+"}").verifyStaffIdentity("a.b.c"));
        for(String invalid:new String[]{"{}","[]","{bad}",USER+"{}",USER.replace("false","\"false\""),USER.replace("12345678-1234-1234-1234-123456789abc","1-1-1-1-1"),USER.replace("2026-01-01T00:00:00Z","not a date"),USER.replace("\"is_anonymous\":false,",""),USER.replace("\"is_anonymous\":false","\"is_anonymous\":false,\"is_anonymous\":true")})
            fails(AuthFailure.Kind.INVALID_RESPONSE,()->auth(200,"application/json",invalid).verifyStaffIdentity("a.b.c"));
        require(auth(200,"application/json",USER.substring(0,USER.length()-1)+",\"user_metadata\":{\"role\":\"ADMINISTRATOR\"}}").verifyStaffIdentity("a.b.c")!=null,"metadata grants no role; output remains ID only");
        var settings=auth(200,"application/json","{\"disable_signup\":true,\"mailer_autoconfirm\":false}").settings();
        require(settings.signupDisabled()&&settings.emailConfirmationRequired(),"settings polarity");
        fails(AuthFailure.Kind.INVALID_RESPONSE,()->auth(200,"application/json","{}").settings());
        fails(AuthFailure.Kind.INVALID_RESPONSE,()->StrictJson.object(bytes("[".repeat(40)+"0"+"]".repeat(40))));
        fails(AuthFailure.Kind.INVALID_RESPONSE,()->StrictJson.object(bytes(" ".repeat(65537))));
        fails(AuthFailure.Kind.INVALID_RESPONSE,()->StrictJson.object(new byte[]{(byte)0xC0,(byte)0xAF}));
        for(String url:new String[]{"http://abcdefghijklmnopqrst.supabase.co","https://secret@abcdefghijklmnopqrst.supabase.co","https://abcdefghijklmnopqrst.supabase.co?secret=yes","https://abcdefghijklmnopqrst.supabase.co.attacker.invalid","https://abcdefghijklmnopqrst.supabase.co/path"}) {
            try{new SupabaseConfig(url,KEY);throw new AssertionError("Unsafe endpoint accepted");}catch(IllegalArgumentException ex){require(!ex.getMessage().contains("secret"),"config redaction");}
        }
        for(String key:new String[]{"sb_secret_neveraccept","eyJ_legacy_role_unknown","sb_publishable_bad\n"}) {
            try{new SupabaseConfig(URL,key);throw new AssertionError("Wrong key accepted");}catch(IllegalArgumentException ex){checks++;}
        }
        require(!new SupabaseConfig(URL,KEY).toString().contains(KEY),"config string redaction");
        boolean[] cancelled={false};var limited=new BoundedHttp.LimitedBody(4);
        limited.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        limited.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        require(cancelled[0]&&limited.getBody().toCompletableFuture().isCompletedExceptionally(),"oversized body cancelled");
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/ok",exchange->{byte[] body=bytes("{}");exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});
        server.createContext("/large",exchange->{byte[] body=new byte[70000];exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","/ok");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/slow",exchange->{exchange.sendResponseHeaders(200,2);try {Thread.sleep(400);}catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();
        try {
            String base="http://localhost:"+server.getAddress().getPort();var http=new BoundedHttp();
            require(http.get(HttpRequest.newBuilder(URI.create(base+"/ok")).timeout(Duration.ofSeconds(2)).build()).status()==200,"real loopback transport");
            require(http.get(HttpRequest.newBuilder(URI.create(base+"/redirect")).timeout(Duration.ofSeconds(2)).build()).status()==302,"redirect not followed");
            fails(AuthFailure.Kind.UNAVAILABLE,()->http.get(HttpRequest.newBuilder(URI.create(base+"/large")).timeout(Duration.ofSeconds(2)).build()));
            long started=System.nanoTime();
            fails(AuthFailure.Kind.UNAVAILABLE,()->http.get(HttpRequest.newBuilder(URI.create(base+"/slow")).timeout(Duration.ofMillis(100)).build()));
            require(Duration.ofNanos(System.nanoTime()-started).toMillis()<1500,"full response deadline");
        } finally {server.stop(0);}
        System.out.println("PASS: "+checks+" Auth/config/JSON/HTTP checks. Fixture and loopback tests only; live Supabase NOT verified.");
    }
}
