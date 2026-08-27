package kr.co.mcmp.softwarecatalog.docker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.co.mcmp.softwarecatalog.docker.model.ContainerDeployResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerHostResourceInfo;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;

@ExtendWith(MockitoExtension.class)
class DockerOperationServiceTest {

    private static final DockerTarget TARGET = new DockerTarget("ns01", "mci-01", "vm-01");
    private static final String CONTAINER_ID = "a".repeat(64);

    @Mock
    private DockerSshCommandExecutor commandExecutor;

    private DockerOperationService service;

    @BeforeEach
    void setUp() {
        service = new DockerOperationService(commandExecutor, new ObjectMapper());
    }

    @Test
    void buildsPinnedJenkinsDeploymentWithLabelsAndPort() {
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(
                        0, "__MCMP_CONTAINER_ID__=" + CONTAINER_ID, ""));
        Map<String, String> params = Map.of(
                "name", "jenkins-test",
                "image", "jenkins/jenkins:2.504.3-lts-jdk17",
                "portBindings", "8080:8080,50000:50000",
                "catalogId", "11",
                "deploymentId", "101",
                "debugKeepAlive", "false");

        ContainerDeployResult result =
                service.runDockerContainer(TARGET, params, List.of("10.0.0.1"), 0);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContainerId()).isEqualTo(CONTAINER_ID);
        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(commandExecutor).execute(eq(TARGET), script.capture());
        assertThat(script.getValue())
                .contains("'jenkins/jenkins:2.504.3-lts-jdk17'")
                .contains("'jenkins-test'")
                .contains("'8080:8080'")
                .contains("'50000:50000'")
                .contains("'mcmp.managed=true'")
                .contains("'mcmp.namespace=ns01'")
                .contains("'mcmp.deployment-id=101'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0:80",
            "65536:80",
            "8080:0",
            "8080:65536",
            "abc:80",
            "8080",
            "8080:80:tcp"
    })
    void rejectsInvalidPortMappings(String portMapping) {
        ContainerDeployResult result = service.runDockerContainer(
                TARGET,
                Map.of("name", "safe-name", "image", "nginx:1.27", "portBindings", portMapping),
                List.of(),
                0);

        assertThat(result.isSuccess()).isFalse();
        verify(commandExecutor, never()).execute(eq(TARGET), anyString());
    }

    @Test
    void acceptsPortBoundaryValues() {
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(
                        0, "__MCMP_CONTAINER_ID__=" + CONTAINER_ID, ""));

        ContainerDeployResult result = service.runDockerContainer(
                TARGET,
                Map.of("name", "port-boundary", "image", "nginx:1.27",
                        "portBindings", "1:1,65535:65535"),
                List.of(),
                0);

        assertThat(result.isSuccess()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "safe;touch-/tmp/pwned",
            "safe$(id)",
            "../escape",
            "white space",
            "quote'name"
    })
    void rejectsContainerNameCommandInjection(String name) {
        ContainerDeployResult result = service.runDockerContainer(
                TARGET,
                Map.of("name", name, "image", "nginx:1.27", "portBindings", "8080:80"),
                List.of(),
                0);

        assertThat(result.isSuccess()).isFalse();
        verify(commandExecutor, never()).execute(eq(TARGET), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nginx;id",
            "nginx $(id)",
            "nginx'bad",
            "../nginx",
            "nginx\nwhoami"
    })
    void rejectsImageReferenceCommandInjection(String image) {
        ContainerDeployResult result = service.runDockerContainer(
                TARGET,
                Map.of("name", "safe", "image", image, "portBindings", "8080:80"),
                List.of(),
                0);

        assertThat(result.isSuccess()).isFalse();
        verify(commandExecutor, never()).execute(eq(TARGET), anyString());
    }

    @Test
    void returnsNullWhenNamedContainerDoesNotExist() {
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(0, "", ""));

        assertThat(service.getContainerId(TARGET, "jenkins-test")).isNull();
    }

    @Test
    void rejectsUntrustedContainerIdentifierForControlAction() {
        assertThatThrownBy(() -> service.stopDockerContainer(TARGET, "name;shutdown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("container ID");
        verify(commandExecutor, never()).execute(eq(TARGET), anyString());
    }

    @Test
    void parsesDockerHostResourceJson() {
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(
                        0, "{\"NCPU\":2,\"MemTotal\":4294967296}", ""));

        DockerHostResourceInfo info = service.getHostResourceInfo(TARGET);

        assertThat(info.getCpuCores()).isEqualTo(2);
        assertThat(info.getMemoryGb()).isEqualTo(4.0);
    }

    @Test
    void limitsRemoteLogVolume() {
        assertThatThrownBy(() -> service.getContainerLogs(TARGET, CONTAINER_ID, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getContainerLogs(TARGET, CONTAINER_ID, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
