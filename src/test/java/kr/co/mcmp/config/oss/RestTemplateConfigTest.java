package kr.co.mcmp.config.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.net.URI;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.Configurable;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class RestTemplateConfigTest {

    @Test
    void actualRequestFactoryKeepsTheCommandSpecificTimeout() throws Exception {
        RestTemplate template = new RestTemplateConfig().restTemplate(1800);
        HttpComponentsClientHttpRequestFactory factory =
                (HttpComponentsClientHttpRequestFactory) template.getRequestFactory();
        try (CloseableHttpClient original = (CloseableHttpClient) factory.getHttpClient()) {
            HttpClient client = mock(HttpClient.class);
            factory.setHttpClient(client);
            when(client.executeOpen(any(), any(), any())).thenAnswer(call -> new BasicClassicHttpResponse(204));
            template.postForEntity(
                    "http://example.invalid/tumblebug/ns/default/cmd/infra/test?nodeId=vm", null, Void.class);
            template.postForEntity(
                    "http://example.invalid/tumblebug/ns/default/resources/objectStorage", null, Void.class);
            ArgumentCaptor<HttpContext> context = ArgumentCaptor.forClass(HttpContext.class);
            verify(client, times(2)).executeOpen(any(), any(), context.capture());
            assertThat(HttpClientContext.adapt(context.getAllValues().get(0)).getRequestConfig().getResponseTimeout().toSeconds())
                    .isEqualTo(1920L);
            // HttpClient applies its default config inside executeOpen, which
            // this mock intentionally replaces. Verify both halves separately:
            // no per-request override, and an explicit finite client default.
            assertThat(context.getAllValues().get(1).getAttribute(HttpClientContext.REQUEST_CONFIG)).isNull();
            assertThat(((Configurable) original).getConfig().getResponseTimeout().toSeconds())
                    .isEqualTo(180L);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 59, 7201})
    void rejectsUnboundedOrTooShortPullTimeouts(int seconds) {
        assertThatThrownBy(() -> new RestTemplateConfig().restTemplate(seconds))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("60 and 7200");
    }

    @Test
    void givesOnlyVmCommandPostsAnExtendedResponseTimeout() {
        HttpClientContext command = RestTemplateConfig.commandContext(
                HttpMethod.POST,
                URI.create("http://mc-infra-manager:1323/tumblebug/ns/default/cmd/infra/my-mci"),
                1800);

        assertThat(command).isNotNull();
        assertThat(command.getRequestConfig().getResponseTimeout().toSeconds()).isEqualTo(1920L);
        assertThat(RestTemplateConfig.commandContext(
                HttpMethod.GET,
                URI.create("http://mc-infra-manager:1323/tumblebug/ns/default/cmd/infra/my-mci"),
                1800)).isNull();
        assertThat(RestTemplateConfig.commandContext(
                HttpMethod.POST,
                URI.create("http://mc-infra-manager:1323/tumblebug/ns/default/resources/objectStorage"),
                1800)).isNull();
    }
}
