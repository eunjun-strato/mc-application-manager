package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class ObjectStorageTunnelProxyTest {
    HttpServer backend;
    ObjectStorageTunnelProxy proxy;
    HttpClient client = HttpClient.newHttpClient();
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> query = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"code\":200,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
        proxy = new ObjectStorageTunnelProxy();
        ReflectionTestUtils.setField(proxy,"backendPort",backend.getAddress().getPort());
    }
    @AfterEach void close() { proxy.close(); backend.stop(0); }
    HttpResponse<String> request(String method,String path,String auth,String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.port()+path))
                .method(method,HttpRequest.BodyPublishers.ofString(body));
        if(auth!=null) request.header("Authorization",auth);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void passesAllowedRequestAndKeepsBearerAndEncodedQuery() throws Exception {
        var response=request("GET",ObjectStorageTunnelProxy.PREFIX+"/objects?storage=test&prefix=a%2Fb","Bearer test-only","");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(authorization.get()).isEqualTo("Bearer test-only");
        assertThat(query.get()).isEqualTo("storage=test&prefix=a%2Fb");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void refusesOtherAmRoutes() throws Exception {
        assertThat(request("GET","/applications","Bearer test-only","").statusCode()).isEqualTo(404);
        assertThat(request("DELETE",ObjectStorageTunnelProxy.PREFIX+"/objects","Bearer test-only","").statusCode()).isEqualTo(404);
        assertThat(calls).hasValue(0);
    }
    @Test void refusesEncodedPathAndTraversal() throws Exception {
        assertThat(request("GET",ObjectStorageTunnelProxy.PREFIX+"/%73torages","Bearer test-only","").statusCode()).isEqualTo(404);
        assertThat(request("GET",ObjectStorageTunnelProxy.PREFIX+"/../storages","Bearer test-only","").statusCode()).isEqualTo(404);
    }
    @Test void requiresBearer() throws Exception {
        assertThat(request("GET",ObjectStorageTunnelProxy.PREFIX+"/storages",null,"").statusCode()).isEqualTo(401);
        assertThat(calls).hasValue(0);
    }
    @Test void boundsRequestBody() throws Exception {
        assertThat(request("POST",ObjectStorageTunnelProxy.PREFIX+"/presigned-url","Bearer test-only","x".repeat(16385))
                .statusCode()).isEqualTo(413);
        assertThat(calls).hasValue(0);
    }
    @Test void healthOnlyForwardsReadiness() throws Exception {
        assertThat(request("GET","/healthz",null,"").statusCode()).isEqualTo(200);
        assertThat(authorization.get()).isNull();
    }
    @Test void rejectsOversizedBackendResponse() throws Exception {
        backend.removeContext("/");
        backend.createContext("/", exchange -> {
            byte[] payload = new byte[ObjectStorageTunnelProxy.MAX_RESPONSE + 1024];
            try {
                exchange.sendResponseHeaders(200,payload.length);
                exchange.getResponseBody().write(payload);
            } catch (java.io.IOException expectedWhenRelayCancels) {
            } finally { exchange.close(); }
        });
        assertThat(request("GET",ObjectStorageTunnelProxy.PREFIX+"/storages","Bearer test-only","")
                .statusCode()).isEqualTo(502);
    }
}
