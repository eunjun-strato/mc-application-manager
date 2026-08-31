package kr.co.mcmp.security.project;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import kr.co.mcmp.softwarecatalog.application.model.ApplicationStatus;
import kr.co.mcmp.softwarecatalog.application.model.DeploymentHistory;
import kr.co.mcmp.softwarecatalog.application.repository.ApplicationStatusRepository;
import kr.co.mcmp.softwarecatalog.application.repository.DeploymentHistoryRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectScopeAuthorizationService {

    public static final String WORKSPACE_ID_HEADER = "X-MCMP-Workspace-ID";
    public static final String PROJECT_ID_HEADER = "X-MCMP-Project-ID";
    public static final String NAMESPACE_ID_HEADER = "X-MCMP-Namespace-ID";

    private static final String VALIDATED_CONTEXT_ATTRIBUTE =
            ProjectScopeAuthorizationService.class.getName() + ".validatedContext";

    private final ProjectScopeSettings settings;
    private final IamProjectAuthorizationClient iamProjectAuthorizationClient;
    private final ApplicationStatusRepository applicationStatusRepository;
    private final DeploymentHistoryRepository deploymentHistoryRepository;

    public boolean isEnabled() {
        return settings.isEnabled();
    }

    /**
     * Returns the namespace carried by a previously IAM-validated project context.
     * In explicitly disabled local mode, a supplied namespace header is returned as-is.
     */
    public String getAuthorizedNamespace(HttpServletRequest request) {
        if (!settings.isEnabled()) {
            return normalize(request.getHeader(NAMESPACE_ID_HEADER));
        }
        return requireAuthorizedContext(request).namespace();
    }

    /**
     * Validates that a request namespace exactly matches the IAM-authorized project namespace.
     */
    public String authorizeNamespace(HttpServletRequest request, String requestedNamespace) {
        String normalizedNamespace = requireValue(requestedNamespace, "namespace");
        if (!settings.isEnabled()) {
            return normalizedNamespace;
        }

        ProjectScopeContext context = requireAuthorizedContext(request);
        if (!context.namespace().equals(normalizedNamespace)) {
            throw ProjectScopeException.forbidden(
                    "The requested namespace does not belong to the selected project.");
        }
        return normalizedNamespace;
    }

    public void authorizeApplicationStatus(HttpServletRequest request, Long applicationStatusId) {
        if (!settings.isEnabled()) {
            return;
        }

        ProjectScopeContext context = requireAuthorizedContext(request);
        ApplicationStatus status = applicationStatusRepository.findById(applicationStatusId)
                .orElseThrow(() -> ProjectScopeException.notFound("Application status was not found."));
        requireTargetInProject(context, status.getNamespace(), "Application status was not found in this project.");
    }

    public void authorizeDeployment(HttpServletRequest request, Long deploymentId) {
        if (!settings.isEnabled()) {
            return;
        }

        ProjectScopeContext context = requireAuthorizedContext(request);
        DeploymentHistory deployment = deploymentHistoryRepository.findById(deploymentId)
                .orElseThrow(() -> ProjectScopeException.notFound("Deployment was not found."));
        requireTargetInProject(context, deployment.getNamespace(), "Deployment was not found in this project.");
    }

    private ProjectScopeContext requireAuthorizedContext(HttpServletRequest request) {
        Object cached = request.getAttribute(VALIDATED_CONTEXT_ATTRIBUTE);
        if (cached instanceof ProjectScopeContext context) {
            return context;
        }

        String authorization = requireBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        String workspaceId = requireValue(request.getHeader(WORKSPACE_ID_HEADER), "workspace ID");
        String projectId = requireValue(request.getHeader(PROJECT_ID_HEADER), "project ID");
        String namespace = requireValue(request.getHeader(NAMESPACE_ID_HEADER), "namespace ID");

        ProjectScopeContext context = new ProjectScopeContext(
                authorization,
                workspaceId,
                projectId,
                namespace);
        if (!iamProjectAuthorizationClient.isAssignedProject(context)) {
            throw ProjectScopeException.forbidden(
                    "The selected project and namespace are not assigned to this user.");
        }

        request.setAttribute(VALIDATED_CONTEXT_ATTRIBUTE, context);
        return context;
    }

    private static void requireTargetInProject(
            ProjectScopeContext context,
            String targetNamespace,
            String notFoundMessage) {
        if (!context.namespace().equals(normalize(targetNamespace))) {
            // Do not reveal whether an ID belongs to another project.
            throw ProjectScopeException.notFound(notFoundMessage);
        }
    }

    private static String requireBearerToken(String authorization) {
        String normalized = normalize(authorization);
        if (normalized.length() <= 7 || !normalized.regionMatches(true, 0, "Bearer ", 0, 7)
                || normalized.substring(7).isBlank()) {
            throw ProjectScopeException.unauthorized("A valid Bearer access token is required.");
        }
        return "Bearer " + normalized.substring(7).trim();
    }

    private static String requireValue(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw ProjectScopeException.badRequest("Project context " + fieldName + " is required.");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
