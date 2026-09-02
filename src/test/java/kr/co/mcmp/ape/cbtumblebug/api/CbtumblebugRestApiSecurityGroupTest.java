package kr.co.mcmp.ape.cbtumblebug.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CbtumblebugRestApiSecurityGroupTest {

    @Mock
    private CbtumblebugRestClient restClient;

    private CbtumblebugRestApi api;

    @BeforeEach
    void setUp() {
        api = new CbtumblebugRestApi(restClient);
        ReflectionTestUtils.setField(api, "cbtumblebugUrl", "mc-infra-manager");
        ReflectionTestUtils.setField(api, "cbtumblebugPort", "1323");
        ReflectionTestUtils.setField(api, "cbtumblebugId", "test-user");
        ReflectionTestUtils.setField(api, "cbtumblebugPass", "test-pass");
    }

    @Test
    void postsOnlyOneNewTcpRuleWithoutExistingAllRules() throws Exception {
        when(restClient.request(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    return url.endsWith("/readyz")
                            ? ResponseEntity.ok("ready")
                            : ResponseEntity.ok("{}");
                });

        api.addInboundTcpFirewallRule("ns01", "sg01", 8888, "203.0.113.10/32");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restClient, atLeast(2)).request(
                urlCaptor.capture(), any(), bodyCaptor.capture(), any(), any());

        String ruleUrl = urlCaptor.getAllValues().stream()
                .filter(url -> url.contains("/resources/securityGroup/sg01/rules"))
                .findFirst()
                .orElseThrow();
        String ruleBody = bodyCaptor.getAllValues().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(body -> body.contains("firewallRules"))
                .findFirst()
                .orElseThrow();

        JsonNode rule = new ObjectMapper().readTree(ruleBody)
                .path("firewallRules")
                .get(0);
        assertThat(ruleUrl).endsWith("/tumblebug/ns/ns01/resources/securityGroup/sg01/rules");
        assertThat(rule.path("Direction").asText()).isEqualTo("inbound");
        assertThat(rule.path("Protocol").asText()).isEqualTo("TCP");
        assertThat(rule.path("CIDR").asText()).isEqualTo("203.0.113.10/32");
        assertThat(rule.path("Ports").asText()).isEqualTo("8888");
        assertThat(ruleBody).doesNotContain("ALL");
    }
}
