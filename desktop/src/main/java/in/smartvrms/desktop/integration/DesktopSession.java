package in.smartvrms.desktop.integration;

import in.smartvrms.application.port.AuthenticationPort;
import in.smartvrms.contracts.SignInResult;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Memory-only session with single-flight refresh; logout/new login cannot be undone by a late refresh. */
public final class DesktopSession {
    private record Entry(SignInResult value, Instant refreshAt) { }
    private final AuthenticationPort auth;
    private final Clock clock;
    private final AtomicReference<Entry> current = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();
    public DesktopSession(AuthenticationPort auth) { this(auth,Clock.systemUTC()); }
    DesktopSession(AuthenticationPort auth,Clock clock) { this.auth=auth;this.clock=clock; }
    public void accept(SignInResult value) {
        if(value==null || value.tokens().expiresInSeconds()<=0) throw new IllegalArgumentException("Invalid session.");
        current.set(entry(value));
    }
    public SignInResult clear() { Entry old=current.getAndSet(null);return old==null?null:old.value(); }
    public boolean isActive() { return current.get()!=null; }
    private Entry entry(SignInResult value) {
        return new Entry(value,clock.instant().plusSeconds(Math.max(1,value.tokens().expiresInSeconds()-30)));
    }
    /** Call only from background work; this may contact the provider. */
    public String accessToken() {
        Entry observed=current.get();
        if(observed==null) throw new SecurityException("Sign in again.");
        if(clock.instant().isBefore(observed.refreshAt())) return observed.value().tokens().accessToken();
        refreshLock.lock();
        try {
            Entry latest=current.get();
            if(latest==null) throw new SecurityException("Sign in again.");
            if(clock.instant().isBefore(latest.refreshAt())) return latest.value().tokens().accessToken();
            SignInResult renewed;
            try { renewed=auth.refresh(latest.value().tokens()); }
            catch(RuntimeException ex) { current.compareAndSet(latest,null);throw new SecurityException("Session expired. Sign in again."); }
            if(!renewed.session().userId().equals(latest.value().session().userId())
                    || !renewed.session().companyId().equals(latest.value().session().companyId())
                    || renewed.tokens().expiresInSeconds()<=0) {
                current.compareAndSet(latest,null);throw new SecurityException("Session validation failed. Sign in again.");
            }
            if(!current.compareAndSet(latest,entry(renewed))) {
                try { auth.logout(renewed.tokens()); } catch(RuntimeException ignored) { }
                throw new SecurityException("Session changed. Sign in again.");
            }
            return renewed.tokens().accessToken();
        } finally { refreshLock.unlock(); }
    }
}
