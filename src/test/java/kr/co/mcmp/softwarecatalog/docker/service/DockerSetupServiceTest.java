package kr.co.mcmp.softwarecatalog.docker.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.mcmp.softwarecatalog.application.exception.ApplicationException;
import kr.co.mcmp.softwarecatalog.docker.model.DockerCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;

@ExtendWith(MockitoExtension.class)
class DockerSetupServiceTest {

    private static final String NAMESPACE = "ns01";
    private static final String MCI_ID = "mci01";
    private static final String VM_ID = "vm01";
    private static final DockerTarget TARGET = new DockerTarget(NAMESPACE, MCI_ID, VM_ID);

    @Mock
    private DockerSshCommandExecutor commandExecutor;

    private DockerSetupService dockerSetupService;

    @BeforeEach
    void setUp() {
        dockerSetupService = new DockerSetupService(commandExecutor);
    }

    @Test
    void keepsInstalledDockerOnUnixSocketWithoutRestartingIt() {
        stub("command -v docker", "Docker version 29.0.0");
        stub("mcmp_override=", "NO_LEGACY_2375_OVERRIDE");
        stub("docker info >/dev/null", "DOCKER_READY");
        stub("ss -lntH", "SECURE_DOCKER_READY");

        dockerSetupService.checkAndInstallDocker(NAMESPACE, MCI_ID, VM_ID);

        verify(commandExecutor, never()).execute(
                eq(TARGET), org.mockito.ArgumentMatchers.contains("get.docker.com"));
        verify(commandExecutor, never()).execute(
                eq(TARGET), org.mockito.ArgumentMatchers.contains("chmod 666"));
    }

    @Test
    void installsMissingDockerAndNeverEnablesTcpApi() {
        stub("command -v docker", "DOCKER_NOT_INSTALLED");
        stub("sudo -n true", "SUDO_OK");
        stub("get.docker.com", "DOCKER_INSTALLED");
        stub("mcmp_override=", "NO_LEGACY_2375_OVERRIDE");
        stub("docker info >/dev/null", "DOCKER_READY");
        stub("ss -lntH", "SECURE_DOCKER_READY");

        dockerSetupService.checkAndInstallDocker(NAMESPACE, MCI_ID, VM_ID);

        verify(commandExecutor).execute(
                eq(TARGET),
                org.mockito.ArgumentMatchers.argThat(command ->
                        command.contains("get.docker.com")
                                && command.contains("systemctl enable --now docker")
                                && !command.contains("tcp://0.0.0.0")));
    }

    @Test
    void removesOnlyTheKnownLegacy2375Override() {
        stub("command -v docker", "Docker version 29.0.0");
        stub("mcmp_override=", "LEGACY_2375_REMOVED");
        stub("docker info >/dev/null", "DOCKER_READY");
        stub("ss -lntH", "SECURE_DOCKER_READY");

        dockerSetupService.checkAndInstallDocker(NAMESPACE, MCI_ID, VM_ID);

        verify(commandExecutor).execute(
                eq(TARGET),
                org.mockito.ArgumentMatchers.argThat(command ->
                        command.contains("/etc/systemd/system/docker.service.d/override.conf")
                                && command.contains("sed -i")
                                && command.contains("systemctl restart docker")));
    }

    @Test
    void refusesToContinueWhen2375StillListens() {
        stub("command -v docker", "Docker version 29.0.0");
        stub("mcmp_override=", "NO_LEGACY_2375_OVERRIDE");
        stub("docker info >/dev/null", "DOCKER_READY");
        stub("ss -lntH", "INSECURE_2375_LISTENER");

        assertThatThrownBy(() ->
                dockerSetupService.checkAndInstallDocker(NAMESPACE, MCI_ID, VM_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("2375 is still active");
    }

    @Test
    void failsClosedWhenPortInspectionIsUnavailable() {
        stub("command -v docker", "Docker version 29.0.0");
        stub("mcmp_override=", "NO_LEGACY_2375_OVERRIDE");
        stub("docker info >/dev/null", "DOCKER_READY");
        stub("ss -lntH", "PORT_CHECK_UNAVAILABLE");

        assertThatThrownBy(() ->
                dockerSetupService.checkAndInstallDocker(NAMESPACE, MCI_ID, VM_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Cannot verify that Docker port 2375 is closed");
    }

    @Test
    void failsWhenDockerIsMissingAndPasswordlessSudoIsUnavailable() {
        stub("command -v docker", "DOCKER_NOT_INSTALLED");
        stub("sudo -n true", "SUDO_REQUIRED");

        assertThatThrownBy(() ->
                dockerSetupService.checkAndInstallDocker(NAMESPACE, MCI_ID, VM_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("passwordless sudo");

        verify(commandExecutor, never()).execute(
                eq(TARGET), org.mockito.ArgumentMatchers.contains("get.docker.com"));
    }

    @Test
    void rejectsUnsafeTargetBeforeAnySshCommand() {
        assertThatThrownBy(() ->
                dockerSetupService.checkAndInstallDocker("../system", MCI_ID, VM_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invalid Docker target");
        verify(commandExecutor, never()).execute(eq(TARGET), anyString());
    }

    private void stub(String commandFragment, String stdout) {
        when(commandExecutor.execute(
                eq(TARGET), org.mockito.ArgumentMatchers.contains(commandFragment)))
                .thenReturn(new DockerCommandResult(0, stdout, ""));
    }
}
