package in.smartvrms.desktop.integration;

import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.contracts.*;
import in.smartvrms.server.api.HttpServerConfig;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class PrototypeSessionChecks {
    static int checks;
    static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    static class Time extends Clock {
        Instant value=Instant.parse("2026-01-01T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
    }
    static class Auth implements AuthenticationPort {
        UUID user=UUID.randomUUID(),company=UUID.randomUUID();AtomicInteger refreshed=new AtomicInteger(),loggedOut=new AtomicInteger();
        boolean fail;CountDownLatch entered,released;
        SignInResult result(String token,long expiry){return new SignInResult(new StaffSession(user,company,"ADMINISTRATOR","",Set.of()),new SessionTokens(token,"private-fixture-refresh",expiry));}
        public boolean isConfigured(){return true;}public SignInResult signIn(String email,char[] password){throw new UnsupportedOperationException();}
        public SignInResult refresh(SessionTokens tokens){refreshed.incrementAndGet();if(entered!=null){entered.countDown();try{if(!released.await(3,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException();}}if(fail)throw new IllegalStateException();return result("renewed",3600);}
        public void logout(SessionTokens tokens){loggedOut.incrementAndGet();}public StaffSession verifySession(String token){throw new UnsupportedOperationException();}
    }
    public static void main(String[] args)throws Exception{
        Auth auth=new Auth();Time clock=new Time();DesktopSession session=new DesktopSession(auth,clock);
        session.accept(auth.result("initial",60));check(session.accessToken().equals("initial"));check(auth.refreshed.get()==0);
        clock.value=clock.value.plusSeconds(31);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{var one=pool.submit(session::accessToken);var two=pool.submit(session::accessToken);check(one.get().equals("renewed"));check(two.get().equals("renewed"));check(auth.refreshed.get()==1);}finally{pool.shutdownNow();}
        check(session.clear()!=null);check(!session.isActive());
        try{session.accessToken();throw new AssertionError();}catch(SecurityException expected){checks++;}
        Auth slow=new Auth();Time secondClock=new Time();DesktopSession second=new DesktopSession(slow,secondClock);second.accept(slow.result("old",1));secondClock.value=secondClock.value.plusSeconds(2);
        slow.entered=new CountDownLatch(1);slow.released=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{var pending=worker.submit(second::accessToken);check(slow.entered.await(2,TimeUnit.SECONDS));check(second.clear()!=null);slow.released.countDown();try{pending.get();throw new AssertionError();}catch(ExecutionException ex){check(ex.getCause() instanceof SecurityException);}check(!second.isActive());check(slow.loggedOut.get()==1);}finally{slow.released.countDown();worker.shutdownNow();}
        Auth broken=new Auth();Time thirdClock=new Time();DesktopSession third=new DesktopSession(broken,thirdClock);third.accept(broken.result("old",1));thirdClock.value=thirdClock.value.plusSeconds(2);broken.fail=true;
        try{third.accessToken();throw new AssertionError();}catch(SecurityException expected){check(!third.isActive());}
        check(HttpServerConfig.fromEnvironment(Map.of("PORT","10000")).bindAddress().getAddress().isLoopbackAddress());
        check(HttpServerConfig.fromEnvironment(Map.of("PORT","10000","SMART_VRMS_BEHIND_TLS_PROXY","true")).bindAddress().getAddress().isAnyLocalAddress());
        for(String invalid:List.of("abc","-1","65536","0")){try{HttpServerConfig.fromEnvironment(Map.of("PORT",invalid));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}}
        System.out.println("PASS: "+checks+" prototype session/refresh/logout and hosted-bind checks. No live provider request.");
    }
}
