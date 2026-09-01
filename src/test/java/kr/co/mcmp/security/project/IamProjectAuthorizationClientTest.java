package kr.co.mcmp.security.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class IamProjectAuthorizationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recognizesOfficialIamWorkspaceProjectResponse() throws Exception {
        JsonNode response = json("""
                {
                  "id": 12,
                  "name": "workspace-a",
                  "projects": [
                    {"id": 34, "nsid": "project-a", "name": "Project A"},
                    {"id": 35, "nsid": "project-b", "name": "Project B"}
                  ]
                }
                """);

        assertThat(IamProjectAuthorizationClient.containsProject(response, "12", "34", "project-a"))
                .isTrue();
        assertThat(IamProjectAuthorizationClient.containsProject(response, "12", "34", "project-b"))
                .isFalse();
        assertThat(IamProjectAuthorizationClient.containsProject(response, "12", "999", "project-a"))
                .isFalse();
        assertThat(IamProjectAuthorizationClient.containsProject(response, "99", "34", "project-a"))
                .isFalse();
    }

    @Test
    void toleratesWrappedResponseAndKnownNamespaceFieldVariants() throws Exception {
        JsonNode response = json("""
                {
                  "data": {
                    "id": "workspace-uuid",
                    "projects": [
                      {"id": "project-uuid", "ns_id": "namespace-a"}
                    ]
                  }
                }
                """);

        assertThat(IamProjectAuthorizationClient.containsProject(
                response, "workspace-uuid", "project-uuid", "namespace-a")).isTrue();
    }

    @Test
    void rejectsEmptyOrMalformedIamResponses() throws Exception {
        assertThat(IamProjectAuthorizationClient.containsProject(null, "12", "34", "project-a"))
                .isFalse();
        assertThat(IamProjectAuthorizationClient.containsProject(json("{}"), "12", "34", "project-a"))
                .isFalse();
        assertThat(IamProjectAuthorizationClient.containsProject(
                json("{\"id\":12,\"projects\":{}}"), "12", "34", "project-a"))
                .isFalse();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
