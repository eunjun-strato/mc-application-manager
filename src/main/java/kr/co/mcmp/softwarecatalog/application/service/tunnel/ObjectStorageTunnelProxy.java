package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Loopback-only allowlisted relay: a VM cannot reach other AM APIs through SSH. */
@Component
public class ObjectStorageTunnelProxy {
    public static final String PREFIX = "/applications/object-storage-gateway";
    static final int MAX_BODY = 16_384;
    static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private static final Set<String> ROUTES = Set.of("GET " + PREFIX + "/storages",
            "GET " + PREFIX + "/objects", "POST " + PREFIX + "/presigned-url");
    @Value("${server.port:18084}")
    private int backendPort = 18084;
    private HttpServer server;
    private ExecutorService executor;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public synchronized int port() throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            executor = new ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64), task -> {
                        Thread thread = new Thread(task, "object-storage-relay");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(executor);
            server.createContext("/", this::forward);
            server.start();
        }
        return server.getAddress().getPort();
    }

    private void forward(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String method = exchange.getRequestMethod();
            boolean health = "GET".equals(method) && "/healthz".equals(uri.toString());
            if (uri.isAbsolute() || uri.getRawAuthority() != null
                    || (!health && !ROUTES.contains(method + " " + uri.getRawPath()))) {
                reply(exchange, 404, new byte[0]);
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!health && (auth == null || !auth.startsWith("Bearer ") || auth.length() <= 7)) {
                reply(exchange, 401, new byte[0]);
                return;
            }
            if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")) {
                reply(exchange, 400, new byte[0]);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                reply(exchange, 413, new byte[0]);
                return;
            }
            URI upstream = URI.create("http://127.0.0.1:" + backendPort + (health ? "/readyz" : uri.toString()));
            HttpRequest.Builder request = HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            if (!health) request.header("Authorization", auth);
            try {
                HttpResponse<byte[]> response = client.send(request.build(), ignored -> new LimitedBody());
                reply(exchange, response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reply(exchange, 503, new byte[0]);
            } catch (IOException | IllegalArgumentException e) {
                // Never log tokens, keys, query strings, response bodies or signed URLs.
                reply(exchange, 502, new byte[0]);
            }
        }
    }

    /** Bound memory while receiving; request timeout covers the complete body. */
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private long received;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_RESPONSE) {
                subscription.cancel();
                delegate.onError(new IOException("Gateway response limit exceeded"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { delegate.onError(failure); }
        @Override public void onComplete() { delegate.onComplete(); }
    }

    private void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }

    @PreDestroy
    public synchronized void close() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        server = null;
    }
}
