package kr.co.mcmp.security.project;

import java.util.Iterator;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

@Component
public class IamProjectAuthorizationClient {

    private final RestClient restClient;

    public IamProjectAuthorizationClient(RestClient.Builder restClientBuilder, ProjectScopeSettings settings) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(toTimeoutMillis(settings.getConnectTimeout()));
        requestFactory.setReadTimeout(toTimeoutMillis(settings.getReadTimeout()));

        this.restClient = restClientBuilder.clone()
                .baseUrl(trimTrailingSlash(settings.getIamBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isAssignedProject(ProjectScopeContext context) {
        try {
            JsonNode response = restClient.get()
                    .uri("/api/users/workspaces/id/{workspaceId}/projects/list", context.workspaceId())
                    .header(HttpHeaders.AUTHORIZATION, context.authorization())
                    .retrieve()
                    .body(JsonNode.class);

            return containsProject(response, context.workspaceId(), context.projectId(), context.namespace());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                throw ProjectScopeException.unauthorized("IAM rejected the access token.");
            }
            if (e.getStatusCode().value() == HttpStatus.FORBIDDEN.value()
                    || e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw ProjectScopeException.forbidden("The user cannot access the requested workspace.");
            }
            if (e.getStatusCode().is4xxClientError()) {
                throw ProjectScopeException.unauthorized("IAM could not validate the project context.");
            }
            throw ProjectScopeException.serviceUnavailable("IAM is temporarily unavailable.");
        } catch (ResourceAccessException e) {
            throw ProjectScopeException.serviceUnavailable("IAM is temporarily unavailable.");
        } catch (RestClientException e) {
            // A malformed/unreadable IAM response must never fall through as an
            // authorization success or an unrelated internal server error.
            throw ProjectScopeException.serviceUnavailable("IAM project validation failed.");
        }
    }

    static boolean containsProject(
            JsonNode response,
            String workspaceId,
            String projectId,
            String namespace) {
        if (response == null || response.isNull()) {
            return false;
        }

        JsonNode workspace = response.hasNonNull("data") ? response.get("data") : response;
        if (!workspaceId.equals(textValue(workspace, "id"))) {
            return false;
        }

        JsonNode projects = workspace.path("projects");
        if (!projects.isArray()) {
            return false;
        }

        Iterator<JsonNode> iterator = projects.elements();
        while (iterator.hasNext()) {
            JsonNode project = iterator.next();
            String responseProjectId = textValue(project, "id");
            String responseNamespace = firstTextValue(project, "nsid", "ns_id", "nsId");
            if (projectId.equals(responseProjectId) && namespace.equals(responseNamespace)) {
                return true;
            }
        }
        return false;
    }

    private static String firstTextValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = textValue(node, fieldName);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static int toTimeoutMillis(java.time.Duration duration) {
        long millis = duration == null ? 0 : duration.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Project scope IAM timeout must be between 1ms and Integer.MAX_VALUE ms");
        }
        return (int) millis;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Project scope IAM base URL must not be blank");
        }
        return value.trim().replaceAll("/+$", "");
    }
}
