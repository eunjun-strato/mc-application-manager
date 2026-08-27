package kr.co.mcmp.softwarecatalog.docker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import kr.co.mcmp.softwarecatalog.application.exception.ApplicationException;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;
import lombok.RequiredArgsConstructor;

/**
 * Prepares Docker on a VM through CB-Tumblebug SSH.
 *
 * The daemon is kept on its local Unix socket. This service never enables a
 * TCP Docker API and explicitly removes the legacy 0.0.0.0:2375 systemd
 * argument previously written by Application Manager.
 */
@Service
@RequiredArgsConstructor
public class DockerSetupService {

    private static final Logger log = LoggerFactory.getLogger(DockerSetupService.class);

    private final DockerSshCommandExecutor commandExecutor;

    public void checkAndInstallDocker(String namespace, String mciId, String vmId) throws ApplicationException {
        log.info("Checking local Docker runtime through SSH for namespace={}, mciId={}, vmId={}",
                namespace, mciId, vmId);
        try {
            DockerTarget target = new DockerTarget(namespace, mciId, vmId);
            String checkResult = execute(target,
                    "if command -v docker >/dev/null 2>&1; then "
                            + "docker --version; "
                            + "else echo 'DOCKER_NOT_INSTALLED'; fi");

            if (checkResult == null || checkResult.contains("DOCKER_NOT_INSTALLED")) {
                installDocker(target);
            }

            removeLegacyInsecureRemoteApi(target);
            ensureDockerServiceRunning(target);
            verifySecureDockerRuntime(target);
        } catch (Exception e) {
            log.error("Failed to prepare Docker through SSH for VM {}", vmId, e);
            throw new ApplicationException("Failed to prepare Docker through SSH: " + safeMessage(e));
        }
    }

    private void installDocker(DockerTarget target) {
        String sudoCheck = execute(target,
                "sudo -n true >/dev/null 2>&1 && echo SUDO_OK || echo SUDO_REQUIRED");
        if (sudoCheck == null || !sudoCheck.contains("SUDO_OK")) {
            throw new IllegalStateException("Docker is not installed and passwordless sudo is unavailable");
        }

        String installCommand = "mcmp_installer=$(mktemp) && "
                + "curl -fsSL https://get.docker.com -o \"$mcmp_installer\" && "
                + "sudo sh \"$mcmp_installer\" && "
                + "rm -f \"$mcmp_installer\" && "
                + "sudo usermod -aG docker \"$(id -un)\" && "
                + "sudo systemctl enable --now docker && "
                + "echo DOCKER_INSTALLED";
        String result = execute(target, installCommand);
        if (result == null || !result.contains("DOCKER_INSTALLED")) {
            throw new IllegalStateException("Docker installation did not complete successfully");
        }
        log.info("Docker installed on VM {} without enabling a TCP API", target.vmId());
    }

    private void removeLegacyInsecureRemoteApi(DockerTarget target) {
        String command = "mcmp_override=/etc/systemd/system/docker.service.d/override.conf; "
                + "if sudo test -f \"$mcmp_override\" "
                + "&& sudo grep -q 'tcp://0.0.0.0:2375' \"$mcmp_override\"; then "
                + "sudo sed -i "
                + "-e 's@[[:space:]]*-H[[:space:]]*tcp://0\\.0\\.0\\.0:2375@@g' "
                + "-e 's@[[:space:]]*--host=tcp://0\\.0\\.0\\.0:2375@@g' "
                + "\"$mcmp_override\" && "
                + "sudo systemctl daemon-reload && sudo systemctl restart docker && "
                + "echo LEGACY_2375_REMOVED; "
                + "else echo NO_LEGACY_2375_OVERRIDE; fi";
        String result = execute(target, command);
        if (result != null && result.contains("LEGACY_2375_REMOVED")) {
            log.warn("Removed legacy Docker 2375 systemd override from VM {}", target.vmId());
        }
    }

    private void ensureDockerServiceRunning(DockerTarget target) {
        String command = "if docker info >/dev/null 2>&1; then echo DOCKER_READY; "
                + "elif sudo -n systemctl enable --now docker >/dev/null 2>&1 "
                + "&& docker info >/dev/null 2>&1; then echo DOCKER_READY; "
                + "else echo DOCKER_NOT_READY; fi";
        String result = execute(target, command);
        if (result == null || !result.contains("DOCKER_READY") || result.contains("DOCKER_NOT_READY")) {
            throw new IllegalStateException(
                    "Docker daemon is not accessible to the Tumblebug SSH user: " + result);
        }
    }

    private void verifySecureDockerRuntime(DockerTarget target) {
        String command = "docker info --format '{{json .ServerVersion}}' >/dev/null 2>&1 "
                + "|| { echo DOCKER_INFO_FAILED; exit 1; }; "
                + "if ! command -v ss >/dev/null 2>&1; then "
                + "echo PORT_CHECK_UNAVAILABLE; "
                + "elif ss -lntH | awk '{print $4}' | grep -Eq '(^|:)2375$'; then "
                + "echo INSECURE_2375_LISTENER; else echo SECURE_DOCKER_READY; fi";
        String result = execute(target, command);
        if (result != null && result.contains("PORT_CHECK_UNAVAILABLE")) {
            throw new IllegalStateException(
                    "Cannot verify that Docker port 2375 is closed because the ss command is unavailable");
        }
        if (result == null || !result.contains("SECURE_DOCKER_READY")
                || result.contains("INSECURE_2375_LISTENER")) {
            throw new IllegalStateException(
                    "Docker must use only its local Unix socket; TCP port 2375 is still active: " + result);
        }
        log.info("Verified Docker Unix-socket access and no 2375 listener on VM {}", target.vmId());
    }

    private String execute(DockerTarget target, String command) {
        return commandExecutor.execute(target, command).stdout();
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
