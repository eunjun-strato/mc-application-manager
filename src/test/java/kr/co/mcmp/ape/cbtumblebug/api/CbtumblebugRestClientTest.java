package kr.co.mcmp.ape.cbtumblebug.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import kr.co.mcmp.ape.cbtumblebug.exception.CbtumblebugException;

class CbtumblebugRestClientTest {
    private static final String URL = "http://example.invalid/tumblebug/ns/default/cmd/infra/test";
    private final RestTemplate template = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
    private final CbtumblebugRestClient client = new CbtumblebugRestClient(template);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsRemoteCommandTimeoutForBothRequestOverloads(boolean useUri) {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatThrownBy(() -> request(useUri))
                .isInstanceOfSatisfying(CbtumblebugException.class, error ->
                        assertThat(error.getCode()).isEqualTo(504))
                .hasMessageContaining("Remote work may still be running");
        server.verify();
    }

    @Test
    void transportFailureDoesNotExposeRequestDetailsToTheUser() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("private-request-details")));
        assertThatThrownBy(() -> request(false))
                .isInstanceOfSatisfying(CbtumblebugException.class, error ->
                        assertThat(error.getCode()).isEqualTo(502))
                .hasMessageContaining("Tumblebug communication failed")
                .hasMessageNotContaining("private-request-details");
    }

    private void request(boolean useUri) {
        ParameterizedTypeReference<String> type = new ParameterizedTypeReference<>() {};
        if (useUri) {
            client.request(URI.create(URL), new HttpHeaders(), null, HttpMethod.POST, type);
        } else {
            client.request(URL, new HttpHeaders(), null, HttpMethod.POST, type);
        }
    }
}
