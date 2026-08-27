package kr.co.mcmp.softwarecatalog.docker.service;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.co.mcmp.softwarecatalog.docker.model.ContainerHealthInfo;
import kr.co.mcmp.softwarecatalog.docker.model.DockerCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects one non-streaming Docker snapshot over SSH.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContainerStatsCollector {

    private static final String INSPECT_BEGIN = "__MCMP_INSPECT_BEGIN__";
    private static final String INSPECT_END = "__MCMP_INSPECT_END__";
    private static final String STATS_BEGIN = "__MCMP_STATS_BEGIN__";
    private static final String STATS_END = "__MCMP_STATS_END__";
    private static final String PORT_ACCESS = "__MCMP_PORT_ACCESS__=";
    private static final Pattern CONTAINER_ID = Pattern.compile("[a-f0-9]{12,64}");
    private static final Pattern SIZE_VALUE =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([kmgtpe]?i?b)$", Pattern.CASE_INSENSITIVE);

    private final DockerSshCommandExecutor commandExecutor;
    private final ObjectMapper objectMapper;

    public ContainerHealthInfo collectContainerStats(DockerTarget target, String containerId) {
        String id = validateContainerId(containerId);
        try {
            String quotedId = DockerSshCommandExecutor.shellQuote(id);
            String script = "printf '" + INSPECT_BEGIN + "\\n'\n"
                    + "docker inspect " + quotedId + " || exit $?\n"
                    + "printf '" + INSPECT_END + "\\n'\n"
                    + "printf '" + STATS_BEGIN + "\\n'\n"
                    + "if [ \"$(docker inspect --format '{{.State.Running}}' " + quotedId
                    + " 2>/dev/null)\" = true ]; then "
                    + "docker stats --no-stream --format '{{json .}}' " + quotedId
                    + " || printf '{}\\n'; else printf '{}\\n'; fi\n"
                    + "printf '" + STATS_END + "\\n'\n"
                    + "mcmp_port=$(docker port " + quotedId
                    + " 2>/dev/null | head -n 1 | awk -F: '{print $NF}')\n"
                    + "mcmp_access=false\n"
                    + "case \"$mcmp_port\" in ''|*[!0-9]*) ;; *) "
                    + "if command -v timeout >/dev/null 2>&1 "
                    + "&& command -v bash >/dev/null 2>&1 "
                    + "&& timeout 2 bash -c \": >/dev/tcp/127.0.0.1/$mcmp_port\" 2>/dev/null; "
                    + "then mcmp_access=true; fi ;; esac\n"
                    + "printf '" + PORT_ACCESS + "%s\\n' \"$mcmp_access\"";

            DockerCommandResult result = commandExecutor.execute(target, script);
            JsonNode inspect = parseInspect(extractSection(result.stdout(), INSPECT_BEGIN, INSPECT_END));
            JsonNode stats = parseStats(extractSection(result.stdout(), STATS_BEGIN, STATS_END));

            String state = textOrNull(inspect.path("State").path("Status"));
            boolean running = inspect.path("State").path("Running").asBoolean(false);
            String healthState = textOrNull(inspect.path("State").path("Health").path("Status"));
            Integer servicePort = extractServicePort(inspect);
            boolean portAccessible = extractMarkerValue(result.stdout(), PORT_ACCESS);

            MemoryValues memory = parseMemoryUsage(textOrNull(stats.path("MemUsage")));
            NetworkValues network = parseNetworkIo(textOrNull(stats.path("NetIO")));

            return ContainerHealthInfo.builder()
                    .status(mapContainerStatus(state))
                    .servicePorts(servicePort)
                    .isPortAccess(servicePort != null && portAccessible)
                    .isHealthCheck(healthState == null ? running : "healthy".equalsIgnoreCase(healthState))
                    .cpuUsage(parsePercentage(textOrNull(stats.path("CPUPerc"))))
                    .memoryUsage(parsePercentage(textOrNull(stats.path("MemPerc"))))
                    .memoryBytes(memory.usedBytes())
                    .memoryLimitBytes(memory.limitBytes())
                    .networkIn(network.inputBytes())
                    .networkOut(network.outputBytes())
                    .oomKilled(inspect.path("State").path("OOMKilled").asBoolean(false))
                    .restartCount(inspect.path("RestartCount").asInt(0))
                    .build();
        } catch (Exception e) {
            log.error("Error collecting container stats through SSH for VM {}, container {}",
                    target.vmId(), id, e);
            return ContainerHealthInfo.builder().status("ERROR").build();
        }
    }

    private JsonNode parseInspect(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalStateException("docker inspect returned no container");
        }
        return root.get(0);
    }

    private JsonNode parseStats(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(json.lines().findFirst().orElse("{}"));
    }

    private String extractSection(String output, String begin, String end) {
        int beginIndex = output.indexOf(begin);
        int endIndex = output.indexOf(end);
        if (beginIndex < 0 || endIndex < 0 || endIndex <= beginIndex) {
            throw new IllegalStateException("Missing structured Docker output section: " + begin);
        }
        return output.substring(beginIndex + begin.length(), endIndex).strip();
    }

    private boolean extractMarkerValue(String output, String marker) {
        return output.lines()
                .filter(line -> line.startsWith(marker))
                .map(line -> line.substring(marker.length()).trim())
                .findFirst()
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    private Integer extractServicePort(JsonNode inspect) {
        JsonNode ports = inspect.path("NetworkSettings").path("Ports");
        if (!ports.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = ports.fields();
        while (fields.hasNext()) {
            JsonNode bindings = fields.next().getValue();
            if (!bindings.isArray()) {
                continue;
            }
            for (JsonNode binding : bindings) {
                String hostPort = textOrNull(binding.path("HostPort"));
                if (hostPort != null && hostPort.matches("\\d+")) {
                    int parsed = Integer.parseInt(hostPort);
                    if (parsed >= 1 && parsed <= 65_535) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    private Double parsePercentage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return roundTwoDecimals(Double.parseDouble(value.replace("%", "").trim()));
        } catch (NumberFormatException e) {
            log.debug("Unable to parse Docker percentage: {}", value);
            return null;
        }
    }

    private MemoryValues parseMemoryUsage(String value) {
        String[] values = splitIoPair(value);
        return new MemoryValues(parseSizeBytes(values[0]), parseSizeBytes(values[1]));
    }

    private NetworkValues parseNetworkIo(String value) {
        String[] values = splitIoPair(value);
        Long input = parseSizeBytes(values[0]);
        Long output = parseSizeBytes(values[1]);
        return new NetworkValues(
                input == null ? null : input.doubleValue(),
                output == null ? null : output.doubleValue());
    }

    private String[] splitIoPair(String value) {
        if (value == null) {
            return new String[] {null, null};
        }
        String[] parts = value.split("\\s*/\\s*", -1);
        return parts.length == 2 ? parts : new String[] {null, null};
    }

    private Long parseSizeBytes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = SIZE_VALUE.matcher(value.trim());
        if (!matcher.matches()) {
            log.debug("Unable to parse Docker byte size: {}", value);
            return null;
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        double multiplier = switch (unit) {
            case "b" -> 1;
            case "kb" -> 1_000;
            case "mb" -> 1_000_000;
            case "gb" -> 1_000_000_000;
            case "tb" -> 1_000_000_000_000L;
            case "pb" -> 1_000_000_000_000_000L;
            case "kib" -> 1_024;
            case "mib" -> 1_048_576;
            case "gib" -> 1_073_741_824;
            case "tib" -> 1_099_511_627_776L;
            case "pib" -> 1_125_899_906_842_624L;
            default -> 1;
        };
        return Math.round(amount * multiplier);
    }

    private String mapContainerStatus(String state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "running" -> "RUNNING";
            case "exited", "dead", "created" -> "STOPPED";
            case "restarting" -> "RESTARTING";
            case "paused" -> "PAUSED";
            default -> "UNKNOWN";
        };
    }

    private String validateContainerId(String containerId) {
        if (containerId == null || !CONTAINER_ID.matcher(containerId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid Docker container ID");
        }
        return containerId.trim();
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record MemoryValues(Long usedBytes, Long limitBytes) {
    }

    private record NetworkValues(Double inputBytes, Double outputBytes) {
    }
}
