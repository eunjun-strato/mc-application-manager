package kr.co.mcmp.softwarecatalog.docker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.co.mcmp.softwarecatalog.docker.model.ContainerHealthInfo;
import kr.co.mcmp.softwarecatalog.docker.model.DockerCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;

@ExtendWith(MockitoExtension.class)
class ContainerStatsCollectorTest {

    private static final DockerTarget TARGET = new DockerTarget("ns01", "mci01", "vm01");
    private static final String CONTAINER_ID = "b".repeat(64);

    @Mock
    private DockerSshCommandExecutor commandExecutor;

    private ContainerStatsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ContainerStatsCollector(commandExecutor, new ObjectMapper());
    }

    @Test
    void parsesRunningHealthyContainerSnapshot() {
        String inspect = """
                [{
                  "Id": "%s",
                  "State": {
                    "Status": "running",
                    "Running": true,
                    "OOMKilled": false,
                    "Health": {"Status": "healthy"}
                  },
                  "RestartCount": 2,
                  "NetworkSettings": {
                    "Ports": {
                      "8080/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8080"}]
                    }
                  }
                }]
                """.formatted(CONTAINER_ID);
        String stats = """
                {"CPUPerc":"1.25%%","MemUsage":"12.5MiB / 1GiB","MemPerc":"1.22%%","NetIO":"1.5kB / 2MiB"}
                """;
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(
                        0, structuredOutput(inspect, stats, true), ""));

        ContainerHealthInfo health = collector.collectContainerStats(TARGET, CONTAINER_ID);

        assertThat(health.getStatus()).isEqualTo("RUNNING");
        assertThat(health.getServicePorts()).isEqualTo(8080);
        assertThat(health.getIsPortAccess()).isTrue();
        assertThat(health.getIsHealthCheck()).isTrue();
        assertThat(health.getCpuUsage()).isEqualTo(1.25);
        assertThat(health.getMemoryUsage()).isEqualTo(1.22);
        assertThat(health.getMemoryBytes()).isEqualTo(13_107_200L);
        assertThat(health.getMemoryLimitBytes()).isEqualTo(1_073_741_824L);
        assertThat(health.getNetworkIn()).isEqualTo(1_500.0);
        assertThat(health.getNetworkOut()).isEqualTo(2_097_152.0);
        assertThat(health.getRestartCount()).isEqualTo(2);
        assertThat(health.getOomKilled()).isFalse();
    }

    @Test
    void mapsStoppedOomContainerWithoutStats() {
        String inspect = """
                [{
                  "State": {
                    "Status": "exited",
                    "Running": false,
                    "OOMKilled": true,
                    "ExitCode": 137
                  },
                  "RestartCount": 4,
                  "NetworkSettings": {"Ports": {}}
                }]
                """;
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(
                        0, structuredOutput(inspect, "{}", false), ""));

        ContainerHealthInfo health = collector.collectContainerStats(TARGET, CONTAINER_ID);

        assertThat(health.getStatus()).isEqualTo("STOPPED");
        assertThat(health.getIsHealthCheck()).isFalse();
        assertThat(health.getIsPortAccess()).isFalse();
        assertThat(health.getCpuUsage()).isNull();
        assertThat(health.getMemoryUsage()).isNull();
        assertThat(health.getOomKilled()).isTrue();
        assertThat(health.getRestartCount()).isEqualTo(4);
    }

    @Test
    void toleratesMalformedOptionalMetricsWithoutCorruptingLifecycleState() {
        String inspect = """
                [{
                  "State": {"Status": "running", "Running": true, "OOMKilled": false},
                  "RestartCount": 0,
                  "NetworkSettings": {"Ports": null}
                }]
                """;
        String stats = """
                {"CPUPerc":"n/a","MemUsage":"unknown","MemPerc":"--","NetIO":"bad"}
                """;
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(
                        0, structuredOutput(inspect, stats, false), ""));

        ContainerHealthInfo health = collector.collectContainerStats(TARGET, CONTAINER_ID);

        assertThat(health.getStatus()).isEqualTo("RUNNING");
        assertThat(health.getIsHealthCheck()).isTrue();
        assertThat(health.getCpuUsage()).isNull();
        assertThat(health.getMemoryBytes()).isNull();
        assertThat(health.getNetworkIn()).isNull();
    }

    @Test
    void returnsErrorForMissingStructuredSections() {
        when(commandExecutor.execute(eq(TARGET), anyString()))
                .thenReturn(new DockerCommandResult(0, "unexpected human output", ""));

        assertThat(collector.collectContainerStats(TARGET, CONTAINER_ID).getStatus())
                .isEqualTo("ERROR");
    }

    @Test
    void rejectsContainerIdInjectionBeforeRemoteExecution() {
        assertThatThrownBy(() -> collector.collectContainerStats(TARGET, "id;cat-/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String structuredOutput(String inspect, String stats, boolean portAccessible) {
        return "__MCMP_INSPECT_BEGIN__\n"
                + inspect.strip() + "\n"
                + "__MCMP_INSPECT_END__\n"
                + "__MCMP_STATS_BEGIN__\n"
                + stats.strip() + "\n"
                + "__MCMP_STATS_END__\n"
                + "__MCMP_PORT_ACCESS__=" + portAccessible;
    }
}
