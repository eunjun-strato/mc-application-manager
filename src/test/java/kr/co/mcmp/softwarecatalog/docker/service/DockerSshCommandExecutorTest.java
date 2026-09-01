package kr.co.mcmp.softwarecatalog.docker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.MciCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;

@ExtendWith(MockitoExtension.class)
class DockerSshCommandExecutorTest {

    private static final DockerTarget TARGET = new DockerTarget("ns01", "mci-01", "vm-01");

    @Mock
    private CbtumblebugRestApi cbtumblebugRestApi;

    private DockerSshCommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DockerSshCommandExecutor(cbtumblebugRestApi);
    }

    @Test
    void returnsStructuredStdoutAndStderr() {
        when(cbtumblebugRestApi.executeMciCommandResult(
                eq("ns01"), eq("mci-01"), org.mockito.ArgumentMatchers.anyString(),
                isNull(), eq("vm-01")))
                .thenReturn(new MciCommandResult(
                        "docker output\n__MCMP_EXIT_CODE__=0\n", "warning"));

        DockerCommandResult result = executor.execute(TARGET, "docker version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("docker output");
        assertThat(result.stderr()).isEqualTo("warning");
    }

    @Test
    void base64TransportKeepsMetacharactersOutOfOuterCommand() {
        String script = "docker run --name 'safe; touch /tmp/pwned' image";
        when(cbtumblebugRestApi.executeMciCommandResult(
                eq("ns01"), eq("mci-01"), org.mockito.ArgumentMatchers.anyString(),
                isNull(), eq("vm-01")))
                .thenReturn(new MciCommandResult("__MCMP_EXIT_CODE__=0\n", ""));

        executor.execute(TARGET, script);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(cbtumblebugRestApi).executeMciCommandResult(
                eq("ns01"), eq("mci-01"), command.capture(), isNull(), eq("vm-01"));
        assertThat(command.getValue()).doesNotContain("touch /tmp/pwned");

        Matcher encodedPayload = Pattern.compile("printf '%s' '([^']+)'").matcher(command.getValue());
        assertThat(encodedPayload.find()).isTrue();
        String decoded = new String(
                Base64.getDecoder().decode(encodedPayload.group(1)), StandardCharsets.UTF_8);
        assertThat(decoded).contains(script);
        assertThat(decoded).contains(DockerSshCommandExecutor.EXIT_MARKER);
    }

    @Test
    void throwsOnNonZeroRemoteExitCode() {
        when(cbtumblebugRestApi.executeMciCommandResult(
                eq("ns01"), eq("mci-01"), org.mockito.ArgumentMatchers.anyString(),
                isNull(), eq("vm-01")))
                .thenReturn(new MciCommandResult(
                        "__MCMP_EXIT_CODE__=125\n", "docker: invalid reference"));

        assertThatThrownBy(() -> executor.execute(TARGET, "docker run bad"))
                .isInstanceOf(DockerCommandException.class)
                .hasMessageContaining("exit=125")
                .hasMessageContaining("invalid reference");
    }

    @Test
    void rejectsResponseWithoutCompletionMarker() {
        when(cbtumblebugRestApi.executeMciCommandResult(
                eq("ns01"), eq("mci-01"), org.mockito.ArgumentMatchers.anyString(),
                isNull(), eq("vm-01")))
                .thenReturn(new MciCommandResult("partial output", "ssh disconnected"));

        assertThatThrownBy(() -> executor.execute(TARGET, "docker ps"))
                .isInstanceOf(DockerCommandException.class)
                .hasMessageContaining("no completion marker");
    }

    @Test
    void shellQuoteTreatsSingleQuoteAsLiteralData() {
        assertThat(DockerSshCommandExecutor.shellQuote("a'b;$(id)"))
                .isEqualTo("'a'\"'\"'b;$(id)'");
    }

    @Test
    void targetRejectsPathAndQueryInjection() {
        assertThatThrownBy(() -> new DockerTarget("../system", "mci", "vm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerTarget("ns", "mci?vmId=other", "vm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerTarget("ns", "mci", "vm/other"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
