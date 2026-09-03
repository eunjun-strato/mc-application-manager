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
        assertThat(root.path("cells").size()).isGreaterThanOrEqualTo(2);
        assertThat(notebook)
                .contains("MCMP_OBJECT_STORAGE_GATEWAY_URL")
                .contains("presigned-url")
                .doesNotContain("AWS_SECRET_ACCESS_KEY")
                .doesNotContain("TUMBLEBUG_PASSWORD");
    }
}
