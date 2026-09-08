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
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CbtumblebugRestApiSecurityGroupTest {

    @Test void workerDiscoveryAndImportStayBehindTumblebug() throws Exception {
        when(restClient.request(anyString(), any(), any(), any(), any())).thenAnswer(i ->
                ResponseEntity.ok(i.getArgument(0,String.class).endsWith("/readyz") ? "ready" : "{}"));
        api.getCspWorkerInfo("aws-seoul", "i-worker");
        api.registerExistingSecurityGroup("default","aws-seoul","vnet","worker","sg-existing");
        var urls=ArgumentCaptor.forClass(String.class);
        var bodies=ArgumentCaptor.forClass(Object.class);
        verify(restClient,atLeast(2)).request(urls.capture(),any(),bodies.capture(),any(),any());
        assertThat(urls.getAllValues()).contains("http://mc-infra-manager:1323/tumblebug/forward/cspvm/i-worker",
                "http://mc-infra-manager:1323/tumblebug/ns/default/resources/securityGroup?option=register");
        String body=bodies.getAllValues().stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(s->s.contains("cspResourceId")).findFirst().orElseThrow();
        assertThat(new ObjectMapper().readTree(body).path("cspResourceId").asText()).isEqualTo("sg-existing");
        assertThat(body).doesNotContain("firewallRules");
    }

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

    @Test
    void detectsAnExistingExactInboundRule() {
        when(restClient.request(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    return url.endsWith("/readyz")
                            ? ResponseEntity.ok("ready")
                            : ResponseEntity.ok("""
                                    {"firewallRules":[
                                      {"Direction":"inbound","Protocol":"TCP","CIDR":"203.0.113.10/32","Port":"8888"}
                                    ]}
                                    """);
                });

        assertThat(api.hasInboundTcpFirewallRule(
                "ns01", "sg01", 8888, "203.0.113.10/32")).isTrue();
    }

    @Test
    void deletesOnlyTheExactTcpRuleThroughTheDedicatedEndpoint() throws Exception {
        when(restClient.request(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    return url.endsWith("/readyz")
                            ? ResponseEntity.ok("ready")
                            : ResponseEntity.ok("{\"success\":true}");
                });

        api.deleteInboundTcpFirewallRule("ns01", "sg01", 8888, "203.0.113.10/32");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.forClass(HttpMethod.class);
        verify(restClient, atLeast(2)).request(
                urlCaptor.capture(), any(), bodyCaptor.capture(), methodCaptor.capture(), any());

        int ruleRequest = -1;
        for (int i = 0; i < urlCaptor.getAllValues().size(); i++) {
            if (urlCaptor.getAllValues().get(i).contains("/resources/securityGroup/sg01/rules")) {
                ruleRequest = i;
                break;
            }
        }
        assertThat(ruleRequest).isGreaterThanOrEqualTo(0);
        assertThat(methodCaptor.getAllValues().get(ruleRequest)).isEqualTo(HttpMethod.DELETE);

        JsonNode rule = new ObjectMapper()
                .readTree((String) bodyCaptor.getAllValues().get(ruleRequest))
                .path("firewallRules")
                .get(0);
        assertThat(rule.path("Direction").asText()).isEqualTo("inbound");
        assertThat(rule.path("Protocol").asText()).isEqualTo("TCP");
        assertThat(rule.path("CIDR").asText()).isEqualTo("203.0.113.10/32");
        assertThat(rule.path("Ports").asText()).isEqualTo("8888");
    }
}
