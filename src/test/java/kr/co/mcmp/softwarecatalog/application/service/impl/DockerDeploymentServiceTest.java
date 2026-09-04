package kr.co.mcmp.softwarecatalog.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.mcmp.softwarecatalog.application.dto.DeploymentRequest;
import kr.co.mcmp.softwarecatalog.application.model.DeploymentHistory;
import kr.co.mcmp.softwarecatalog.application.repository.DeploymentHistoryRepository;
import kr.co.mcmp.softwarecatalog.application.service.ApplicationHistoryService;

class DockerDeploymentServiceTest {

    @ParameterizedTest
    @CsvSource({"false,FAILED", "true,SUCCESS"})
    void persistsFinalHistoryStatusInsteadOfLeavingItInProgress(boolean success, String expectedStatus) {
        DockerDeploymentService service = mock(DockerDeploymentService.class, CALLS_REAL_METHODS);
        DeploymentHistoryRepository repository = mock(DeploymentHistoryRepository.class);
        ReflectionTestUtils.setField(service, "deploymentHistoryRepository", repository);
        ReflectionTestUtils.setField(service, "applicationHistoryService", mock(ApplicationHistoryService.class));
        DeploymentHistory history = new DeploymentHistory();
        history.setStatus("IN_PROGRESS");

        ReflectionTestUtils.invokeMethod(service, "processDeploymentResults", history, null,
                success ? List.of("vm") : List.of(),
                success ? List.of() : List.of("vm (image pull timed out)"), new DeploymentRequest());

        assertThat(history.getStatus()).isEqualTo(expectedStatus);
        assertThat(history.getUpdatedAt()).isNotNull();
        verify(repository).save(history);
    }

    @Test
    void batchSummaryDoesNotReplaceTheFirstVmsAlreadySavedIndividualStatus() {
        DockerDeploymentService service = mock(DockerDeploymentService.class, CALLS_REAL_METHODS);
        DeploymentHistoryRepository repository = mock(DeploymentHistoryRepository.class);
        ReflectionTestUtils.setField(service, "deploymentHistoryRepository", repository);
        ReflectionTestUtils.setField(service, "applicationHistoryService", mock(ApplicationHistoryService.class));
        DeploymentHistory history = new DeploymentHistory();
        history.setStatus("FAILED");
        DeploymentRequest request = new DeploymentRequest();
        request.setVmIds(List.of("failed-vm", "successful-vm"));

        ReflectionTestUtils.invokeMethod(service, "processDeploymentResults", history, null,
                List.of("successful-vm"), List.of("failed-vm"), request);

        assertThat(history.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        verifyNoInteractions(repository);
    }

    @Test
    void bundledObjectStorageNotebookIsValidAndDoesNotContainCredentials() throws Exception {
        String notebook = DockerDeploymentService.jupyterObjectStorageNotebook();
        JsonNode root = new ObjectMapper().readTree(notebook);

        assertThat(root.path("nbformat").asInt()).isEqualTo(4);
        assertThat(root.path("cells").size()).isEqualTo(15);
        assertThat(root.path("metadata").path("mcmp").path("templateVersion").asInt()).isEqualTo(2);
        assertThat(notebook)
                .contains("MCMP_OBJECT_STORAGE_GATEWAY_URL")
                .contains("presigned-url")
                .doesNotContain("AWS_SECRET_ACCESS_KEY")
                .doesNotContain("TUMBLEBUG_PASSWORD");
    }

    @Test
    void notebookIncludesAnalysisFlowWithoutAutomaticWrites() throws Exception {
        JsonNode root = new ObjectMapper().readTree(DockerDeploymentService.jupyterObjectStorageNotebook());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonNode cell : root.path("cells")) {
            assertThat(ids.add(cell.path("id").asText())).isTrue();
            if ("code".equals(cell.path("cell_type").asText())) {
                assertThat(cell.path("outputs").isEmpty()).isTrue();
                assertThat(cell.path("execution_count").isNull()).isTrue();
            }
            if ("mcmp-export".equals(cell.path("id").asText())) {
                for (JsonNode line : cell.path("source")) {
                    assertThat(line.asText().strip()).startsWith("#");
                }
            }
        }
        assertThat(ids).contains("mcmp-storages", "mcmp-settings", "mcmp-objects",
                "mcmp-preview", "mcmp-chart", "mcmp-export");
    }
}
