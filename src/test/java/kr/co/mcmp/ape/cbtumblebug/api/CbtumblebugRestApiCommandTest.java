package kr.co.mcmp.ape.cbtumblebug.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import kr.co.mcmp.ape.cbtumblebug.dto.MciCommandResult;
import kr.co.mcmp.ape.cbtumblebug.exception.CbtumblebugException;

@ExtendWith(MockitoExtension.class)
class CbtumblebugRestApiCommandTest {

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
    void parsesObjectShapedStdoutAndStderr() {
        String response = """
                {
                  "results": [{
                    "stdout": {"0": "line-one\\nline-two\\n"},
                    "stderr": {"0": "warning\\n"}
                  }]
                }
                """;
        stubReadyAndCommand(response);

        MciCommandResult result =
                api.executeMciCommandResult("ns01", "mci01", "docker ps", null, "vm01");

        assertThat(result.stdout()).isEqualTo("line-one\nline-two\n");
        assertThat(result.stderr()).isEqualTo("warning\n");
    }

    @Test
    void joinsArrayOutputWithoutDiscardingEntries() {
        String response = """
                {
                  "results": [{
                    "stdout": ["first", "second"],
                    "stderr": []
                  }]
                }
                """;
        stubReadyAndCommand(response);

        MciCommandResult result =
                api.executeMciCommandResult("ns01", "mci01", "docker ps", null, "vm01");

        assertThat(result.stdout()).isEqualTo("first\nsecond");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void usesNodeIdToTargetExactlyOneVm() {
        String response = """
                {
                  "results": [{
                    "nodeId": "vm01",
                    "stdout": "ok",
                    "stderr": ""
                  }]
                }
                """;
        stubReadyAndCommand(response);

        api.executeMciCommandResult("ns01", "mci01", "docker ps", null, "vm01");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restClient, atLeast(2)).request(urlCaptor.capture(), any(), any(), any(), any());
        String commandUrl = urlCaptor.getAllValues().stream()
                .filter(url -> url.contains("/cmd/infra/mci01"))
                .findFirst()
                .orElseThrow();

        assertThat(commandUrl).contains("nodeId=vm01").doesNotContain("vmId=");
    }

    @Test
    void refusesAmbiguousMultiVmResponse() {
        String response = """
                {
                  "results": [
                    {"stdout": "one", "stderr": ""},
                    {"stdout": "two", "stderr": ""}
                  ]
                }
                """;
        stubReadyAndCommand(response);

        assertThatThrownBy(() ->
                api.executeMciCommandResult("ns01", "mci01", "docker ps", null, "vm01"))
                .isInstanceOf(CbtumblebugException.class)
                .hasMessageContaining("Expected one VM command result");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubReadyAndCommand(String commandResponse) {
        when(restClient.request(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    return url.endsWith("/readyz")
                            ? ResponseEntity.ok("ready")
                            : ResponseEntity.ok(commandResponse);
                });
    }
}
