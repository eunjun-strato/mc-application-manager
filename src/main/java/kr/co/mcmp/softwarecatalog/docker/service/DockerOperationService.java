package kr.co.mcmp.softwarecatalog.docker.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.co.mcmp.softwarecatalog.docker.model.ContainerDeployResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerHostResourceInfo;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Docker lifecycle operations executed on one VM through CB-Tumblebug SSH.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DockerOperationService {

    private static final Pattern IMAGE_REFERENCE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}");
    private static final Pattern CONTAINER_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern CONTAINER_ID = Pattern.compile("[a-f0-9]{12,64}");
    private static final Pattern IP_ADDRESS = Pattern.compile("[A-Fa-f0-9:.]{2,64}");
    private static final Pattern CONTAINER_ID_MARKER =
            Pattern.compile("(?:^|\\n)__MCMP_CONTAINER_ID__=([a-f0-9]{12,64})(?:\\n|$)");

    private final DockerSshCommandExecutor commandExecutor;
    private final ObjectMapper objectMapper;

    public DockerHostResourceInfo getHostResourceInfo(DockerTarget target) {
        try {
            DockerCommandResult result = commandExecutor.execute(
                    target, "docker info --format '{{json .}}'");
            JsonNode info = objectMapper.readTree(result.stdout());
            Integer cpuCores = info.path("NCPU").isNumber() ? info.path("NCPU").asInt() : null;
            Long totalMemoryBytes = info.path("MemTotal").isNumber() ? info.path("MemTotal").asLong() : null;
            Double memoryGb = totalMemoryBytes == null
                    ? null
                    : totalMemoryBytes / (1024.0 * 1024.0 * 1024.0);
            return new DockerHostResourceInfo(cpuCores, memoryGb);
        } catch (Exception e) {
            log.warn("Failed to resolve Docker host resources for VM {}", target.vmId(), e);
            return null;
        }
    }

    public ContainerDeployResult runDockerContainer(
            DockerTarget target,
            Map<String, String> deployParams,
            List<String> vmPublicIps,
            int vmIndex) {
        try {
            if (deployParams == null) {
                throw new IllegalArgumentException("Deployment parameters are required");
            }
            String imageName = validateImageReference(deployParams.get("image"));
            String containerName = validateContainerName(deployParams.get("name"));
            List<PortMapping> portMappings = parsePortBindings(deployParams.get("portBindings"));
            Map<String, String> environmentVariables =
                    getEnvironmentVariables(imageName, vmPublicIps, vmIndex, target.vmId());

            String createCommand = buildCreateCommand(
                    target, deployParams, imageName, containerName, portMappings, environmentVariables);
            String script = "if ! docker image inspect "
                    + DockerSshCommandExecutor.shellQuote(imageName)
                    + " >/dev/null 2>&1; then docker pull "
                    + DockerSshCommandExecutor.shellQuote(imageName)
                    + " || exit $?; fi\n"
                    + "mcmp_container_id=$(" + createCommand + ") || exit $?\n"
                    + "docker start \"$mcmp_container_id\" >/dev/null || exit $?\n"
                    + "mcmp_attempt=0\n"
                    + "while [ \"$mcmp_attempt\" -lt 60 ]; do\n"
                    + "  mcmp_state=$(docker inspect --format '{{.State.Status}}' "
                    + "\"$mcmp_container_id\" 2>/dev/null) || exit $?\n"
                    + "  if [ \"$mcmp_state\" = running ]; then\n"
                    + "    printf '__MCMP_CONTAINER_ID__=%s\\n' \"$mcmp_container_id\"\n"
                    + "    exit 0\n"
                    + "  fi\n"
                    + "  if [ \"$mcmp_state\" = exited ] || [ \"$mcmp_state\" = dead ]; then\n"
                    + "    docker logs --tail 50 \"$mcmp_container_id\" >&2 || true\n"
                    + "    exit 1\n"
                    + "  fi\n"
                    + "  mcmp_attempt=$((mcmp_attempt + 1))\n"
                    + "  sleep 1\n"
                    + "done\n"
                    + "echo 'Container did not reach running state within 60 seconds' >&2\n"
                    + "exit 124";

            DockerCommandResult result = commandExecutor.execute(target, script);
            String containerId = extractContainerId(result.stdout());

            if (imageName.toLowerCase().contains("redis")
                    && vmPublicIps != null && !vmPublicIps.isEmpty() && vmIndex == 0) {
                configureRedisCluster(target, containerId, vmPublicIps);
            }
            return new ContainerDeployResult(containerId, "Container started", true);
        } catch (Exception e) {
            log.error("Error running Docker container through SSH on VM {}", target.vmId(), e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ContainerDeployResult(null, message, false);
        }
    }

    public String getContainerId(DockerTarget target, String containerName) {
        String safeName = validateContainerName(containerName);
        String script = "mcmp_id=$(docker inspect --type container --format '{{.Id}}' "
                + DockerSshCommandExecutor.shellQuote(safeName) + " 2>/dev/null || true)\n"
                + "printf '%s\\n' \"$mcmp_id\"";
        DockerCommandResult result = commandExecutor.execute(target, script);
        String value = result.stdout().trim();
        if (value.isEmpty()) {
            return null;
        }
        return validateContainerId(value);
    }

    public String getDockerContainerStatus(DockerTarget target, String containerId) {
        String id = validateContainerId(containerId);
        DockerCommandResult result = commandExecutor.execute(
                target,
                "docker inspect --type container --format '{{.State.Status}}' "
                        + DockerSshCommandExecutor.shellQuote(id));
        return result.stdout().trim();
    }

    public String stopDockerContainer(DockerTarget target, String containerId) {
        executeContainerAction(target, "stop", containerId);
        return "Container stopped successfully";
    }

    public String startDockerContainer(DockerTarget target, String containerId) {
        executeContainerAction(target, "start", containerId);
        return "Container started successfully";
    }

    public String restartDockerContainer(DockerTarget target, String containerId) {
        executeContainerAction(target, "restart", containerId);
        return "Container restarted successfully";
    }

    public String removeDockerContainer(DockerTarget target, String containerId) {
        String id = validateContainerId(containerId);
        String script = "if docker inspect --type container "
                + DockerSshCommandExecutor.shellQuote(id) + " >/dev/null 2>&1; then "
                + "docker rm -f -v " + DockerSshCommandExecutor.shellQuote(id)
                + " >/dev/null; echo REMOVED; else echo ALREADY_REMOVED; fi";
        DockerCommandResult result = commandExecutor.execute(target, script);
        return result.stdout().contains("ALREADY_REMOVED")
                ? "Container already removed"
                : "Container removed successfully";
    }

    public boolean isContainerRunning(DockerTarget target, String containerId) {
        try {
            return "running".equalsIgnoreCase(getDockerContainerStatus(target, containerId));
        } catch (DockerCommandException e) {
            log.warn("Failed to resolve container state on VM {}: {}", target.vmId(), e.getMessage());
            return false;
        }
    }

    public List<String> getContainerLogs(DockerTarget target, String containerId, int maxLines) {
        String id = validateContainerId(containerId);
        if (maxLines < 1 || maxLines > 1_000) {
            throw new IllegalArgumentException("Docker log line limit must be between 1 and 1000");
        }
        DockerCommandResult result = commandExecutor.execute(
                target,
                "docker logs --tail " + maxLines + " "
                        + DockerSshCommandExecutor.shellQuote(id) + " 2>&1");
        return result.stdout().lines().toList();
    }

    private void executeContainerAction(DockerTarget target, String action, String containerId) {
        if (!List.of("start", "stop", "restart").contains(action)) {
            throw new IllegalArgumentException("Unsupported Docker action: " + action);
        }
        String id = validateContainerId(containerId);
        commandExecutor.execute(
                target,
                "docker " + action + " " + DockerSshCommandExecutor.shellQuote(id) + " >/dev/null");
    }

    private String buildCreateCommand(
            DockerTarget target,
            Map<String, String> deployParams,
            String imageName,
            String containerName,
            List<PortMapping> portMappings,
            Map<String, String> environmentVariables) {
        List<String> arguments = new ArrayList<>();
        arguments.add("docker");
        arguments.add("create");
        arguments.add("--name");
        arguments.add(containerName);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("mcmp.managed", "true");
        labels.put("mcmp.namespace", target.namespace());
        labels.put("mcmp.mci-id", target.mciId());
        labels.put("mcmp.vm-id", target.vmId());
        putNumericLabel(labels, "mcmp.catalog-id", deployParams.get("catalogId"));
        putNumericLabel(labels, "mcmp.deployment-id", deployParams.get("deploymentId"));
        labels.forEach((key, value) -> {
            arguments.add("--label");
            arguments.add(key + "=" + value);
        });

        for (PortMapping mapping : portMappings) {
            arguments.add("-p");
            arguments.add(mapping.hostPort() + ":" + mapping.containerPort());
        }
        environmentVariables.forEach((key, value) -> {
            arguments.add("-e");
            arguments.add(key + "=" + value);
        });
        arguments.add(imageName);
        if (Boolean.parseBoolean(deployParams.getOrDefault("debugKeepAlive", "false"))) {
            arguments.add("tail");
            arguments.add("-f");
            arguments.add("/dev/null");
        }
        return shellCommand(arguments);
    }

    private void putNumericLabel(Map<String, String> labels, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid numeric Docker label value for " + key);
        }
        labels.put(key, value);
    }

    private String shellCommand(List<String> arguments) {
        return arguments.stream()
                .map(DockerSshCommandExecutor::shellQuote)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
    }

    private String extractContainerId(String output) {
        Matcher matcher = CONTAINER_ID_MARKER.matcher(output == null ? "" : output);
        if (!matcher.find()) {
            throw new IllegalStateException("Docker create returned no container ID");
        }
        return validateContainerId(matcher.group(1));
    }

    private String validateImageReference(String imageReference) {
        if (imageReference == null || !IMAGE_REFERENCE.matcher(imageReference).matches()) {
            throw new IllegalArgumentException("Invalid Docker image reference: " + imageReference);
        }
        return imageReference;
    }

    private String validateContainerName(String containerName) {
        if (containerName == null || !CONTAINER_NAME.matcher(containerName).matches()) {
            throw new IllegalArgumentException("Invalid Docker container name: " + containerName);
        }
        return containerName;
    }

    private String validateContainerId(String containerId) {
        if (containerId == null || !CONTAINER_ID.matcher(containerId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid Docker container ID");
        }
        return containerId.trim();
    }

    private List<PortMapping> parsePortBindings(String portBindings) {
        List<PortMapping> mappings = new ArrayList<>();
        if (portBindings == null || portBindings.isBlank()) {
            return mappings;
        }
        for (String rawMapping : portBindings.split(",")) {
            String mapping = rawMapping.trim();
            if (mapping.isEmpty()) {
                continue;
            }
            String[] parts = mapping.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid Docker port mapping: " + mapping);
            }
            int hostPort = parsePort(parts[0], "host");
            int containerPort = parsePort(parts[1], "container");
            mappings.add(new PortMapping(hostPort, containerPort));
        }
        return mappings;
    }

    private int parsePort(String value, String type) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(type + " port must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + type + " port: " + value, e);
        }
    }

    private void configureRedisCluster(
            DockerTarget target, String containerId, List<String> vmPublicIps) {
        String id = validateContainerId(containerId);
        try {
            for (int i = 1; i < vmPublicIps.size(); i++) {
                String peerIp = validateIpAddress(vmPublicIps.get(i));
                commandExecutor.execute(
                        target,
                        "docker exec " + DockerSshCommandExecutor.shellQuote(id)
                                + " redis-cli -h localhost -p 6379 cluster meet "
                                + DockerSshCommandExecutor.shellQuote(peerIp) + " 6379");
            }
            commandExecutor.execute(
                    target,
                    "docker exec " + DockerSshCommandExecutor.shellQuote(id)
                            + " redis-cli -h localhost -p 6379 cluster addslots 0 1 2 3 4 5");
        } catch (Exception e) {
            log.error("Failed to configure Redis cluster for container {}", id, e);
        }
    }

    private String validateIpAddress(String value) {
        if (value == null || !IP_ADDRESS.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid cluster peer IP address");
        }
        return value;
    }

    private Map<String, String> getEnvironmentVariables(
            String imageName, List<String> vmPublicIps, int vmIndex, String vmId) {
        Map<String, String> envVars = new LinkedHashMap<>();
        String lowerImageName = imageName.toLowerCase();

        if (lowerImageName.contains("elasticsearch")) {
            envVars.put("cluster.name", "elasticsearch-cluster");
            envVars.put("network.host", "0.0.0.0");
            envVars.put("http.port", "9200");
            envVars.put("transport.port", "9300");
            envVars.put("xpack.security.enabled", "false");
            envVars.put("xpack.ml.enabled", "false");
            envVars.put("ES_JAVA_OPTS", "-Xms256m -Xmx256m");
            envVars.put("bootstrap.memory_lock", "false");
            if (vmPublicIps != null && !vmPublicIps.isEmpty() && vmIndex >= 0) {
                envVars.put("node.name", String.format("es-%02d", vmIndex + 1));
                envVars.put("discovery.seed_hosts", String.join(",", vmPublicIps));
                List<String> nodeNames = new ArrayList<>();
                for (int i = 0; i < vmPublicIps.size(); i++) {
                    nodeNames.add(String.format("es-%02d", i + 1));
                }
                envVars.put("cluster.initial_master_nodes", String.join(",", nodeNames));
                if (vmIndex < vmPublicIps.size()) {
                    envVars.put("network.publish_host", vmPublicIps.get(vmIndex));
                }
            } else {
                envVars.put("discovery.type", "single-node");
            }
        } else if (lowerImageName.contains("redis")) {
            envVars.put("REDIS_PASSWORD", "");
            if (vmPublicIps != null && !vmPublicIps.isEmpty() && vmIndex >= 0) {
                envVars.put("REDIS_CLUSTER_ENABLED", "yes");
                envVars.put("REDIS_CLUSTER_ANNOUNCE_IP", vmPublicIps.get(vmIndex));
                envVars.put("REDIS_CLUSTER_ANNOUNCE_PORT", "7000");
                envVars.put("REDIS_CLUSTER_ANNOUNCE_BUS_PORT", "17000");
                envVars.put("REDIS_CLUSTER_NODES", String.join(" ", vmPublicIps));
                envVars.put("REDIS_CLUSTER_REPLICAS", "1");
                envVars.put("REDIS_PORT", "6379");
                envVars.put("REDIS_CLUSTER_PORT", "7000");
                envVars.put("REDIS_BIND", "0.0.0.0");
                envVars.put("REDIS_PROTECTED_MODE", "no");
                envVars.put("REDIS_CLUSTER_CONFIG_NODES", "6");
                envVars.put("REDIS_CLUSTER_TIMEOUT", "5000");
                envVars.put("REDIS_CLUSTER_REQUIRE_FULL_COVERAGE", "no");
                envVars.put("REDIS_LOGLEVEL", "notice");
                envVars.put("REDIS_DAEMONIZE", "no");
                envVars.put("REDIS_SERVER_ARGS",
                        "--cluster-enabled yes --cluster-config-file nodes.conf "
                                + "--cluster-node-timeout 5000 --appendonly yes");
            } else {
                envVars.put("REDIS_PORT", "6379");
                envVars.put("REDIS_BIND", "0.0.0.0");
                envVars.put("REDIS_PROTECTED_MODE", "no");
                envVars.put("REDIS_DAEMONIZE", "no");
            }
        } else if (lowerImageName.contains("mariadb") || lowerImageName.contains("mysql")) {
            envVars.put("MYSQL_ROOT_PASSWORD", "password");
            envVars.put("MYSQL_DATABASE", "testdb");
            envVars.put("MYSQL_USER", "testuser");
            envVars.put("MYSQL_PASSWORD", "testpass");
        }
        return envVars;
    }

    private record PortMapping(int hostPort, int containerPort) {
    }
}
