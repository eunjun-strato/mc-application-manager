package kr.co.mcmp.softwarecatalog.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class DockerDeploymentServiceTest {

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
