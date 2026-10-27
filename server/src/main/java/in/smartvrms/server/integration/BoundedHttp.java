package in.smartvrms.server.integration;

import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded response buffering and request deadlines; redirects and automatic application retries disabled. */
public final class BoundedHttp {
    public static final int MAX_BYTES=65_536;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public SupabaseAuth.Response send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(request,info->new LimitedBody(MAX_BYTES));
        try {
            HttpResponse<byte[]> response=pending.get(request.timeout().orElse(Duration.ofSeconds(15)).toMillis(),TimeUnit.MILLISECONDS);
            return new SupabaseAuth.Response(response.statusCode(),response.headers().firstValue("Content-Type").orElse(""),response.body());
        } catch(InterruptedException ex) { pending.cancel(true);Thread.currentThread().interrupt();throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE); }
        catch(ExecutionException|TimeoutException|CancellationException ex) { pending.cancel(true);throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE); }
    }
    public SupabaseAuth.Response get(HttpRequest request) {
        return send(request);
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final long maximum;
        private Flow.Subscription subscription;
        private long received;
        private boolean ended;
        LimitedBody(long maximum) { this.maximum=maximum; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value;delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> data) {
            if(ended) return;
            for(ByteBuffer buffer:data) received+=buffer.remaining();
            if(received>maximum) { ended=true;subscription.cancel();delegate.onError(new IOException("Response exceeds size limit"));return; }
            delegate.onNext(data);
        }
        public void onError(Throwable error) { if(!ended) {ended=true;delegate.onError(error);} }
        public void onComplete() { if(!ended) {ended=true;delegate.onComplete();} }
    }
}
