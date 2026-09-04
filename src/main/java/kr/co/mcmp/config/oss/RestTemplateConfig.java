package kr.co.mcmp.config.oss;

import java.net.URI;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean(name = "customRestTemplate")
    public RestTemplate restTemplate(
            @Value("${app.vm-deployment.image-pull-timeout-seconds:1800}") int imagePullTimeoutSeconds) {
        if (imagePullTimeoutSeconds < 60 || imagePullTimeoutSeconds > 7200) {
            throw new IllegalArgumentException("VM image pull timeout must be between 60 and 7200 seconds");
        }
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(50);

        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                // Reset the usual three-minute read limit on every non-command
                // request, even when it reuses a connection from a long pull.
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(180)).build())
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setHttpClient(httpClient);
        // Only remote VM commands need to outlive a large image pull. Keep the
        // existing timeout behavior for metadata, Object Storage and other APIs.
        factory.setHttpContextFactory((method, uri) -> commandContext(method, uri, imagePullTimeoutSeconds));
        return new RestTemplate(factory);
    }

    static HttpClientContext commandContext(HttpMethod method, URI uri, int imagePullTimeoutSeconds) {
        if (!HttpMethod.POST.equals(method)
                || !uri.getPath().matches("/tumblebug/ns/[^/]+/cmd/infra/[^/]+")) {
            return null;
        }
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds((long) imagePullTimeoutSeconds + 120))
                .build());
        return context;
    }
}
