package kr.co.mcmp.security.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import kr.co.mcmp.softwarecatalog.application.model.ApplicationStatus;
import kr.co.mcmp.softwarecatalog.application.model.DeploymentHistory;
import kr.co.mcmp.softwarecatalog.application.repository.ApplicationStatusRepository;
import kr.co.mcmp.softwarecatalog.application.repository.DeploymentHistoryRepository;

@ExtendWith(MockitoExtension.class)
class ProjectScopeAuthorizationServiceTest {

    @Mock
    private IamProjectAuthorizationClient iamClient;
    @Mock
    private ApplicationStatusRepository applicationStatusRepository;
    @Mock
    private DeploymentHistoryRepository deploymentHistoryRepository;

    private ProjectScopeSettings settings;
    private ProjectScopeAuthorizationService service;

    @BeforeEach
    void setUp() {
        settings = new ProjectScopeSettings();
        settings.setEnabled(true);
        service = new ProjectScopeAuthorizationService(
                settings,
                iamClient,
                applicationStatusRepository,
                deploymentHistoryRepository);
    }

    @Test
    void acceptsNamespaceAssignedToAuthenticatedProjectAndCachesContextPerRequest() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");
        when(iamClient.isAssignedProject(context("12", "34", "project-a"))).thenReturn(true);

        assertThat(service.authorizeNamespace(request, "project-a")).isEqualTo("project-a");
        assertThat(service.getAuthorizedNamespace(request)).isEqualTo("project-a");

        verify(iamClient).isAssignedProject(context("12", "34", "project-a"));
    }

    @Test
    void rejectsNamespaceChangedInRequestEvenWithValidProjectContext() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");
        when(iamClient.isAssignedProject(context("12", "34", "project-a"))).thenReturn(true);

        assertThatThrownBy(() -> service.authorizeNamespace(request, "project-b"))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectsMissingBearerTokenBeforeCallingIam() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");
        request.removeHeader(HttpHeaders.AUTHORIZATION);

        assertThatThrownBy(() -> service.getAuthorizedNamespace(request))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(iamClient, never()).isAssignedProject(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsProjectNotAssignedByIam() {
        MockHttpServletRequest request = authorizedRequest("12", "999", "project-a");
        when(iamClient.isAssignedProject(context("12", "999", "project-a"))).thenReturn(false);

        assertThatThrownBy(() -> service.getAuthorizedNamespace(request))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void failsClosedWhenIamIsUnavailable() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");
        when(iamClient.isAssignedProject(context("12", "34", "project-a")))
                .thenThrow(ProjectScopeException.serviceUnavailable("IAM is temporarily unavailable."));

        assertThatThrownBy(() -> service.getAuthorizedNamespace(request))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void preservesIamUnauthorizedResultForExpiredOrInvalidToken() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");
        when(iamClient.isAssignedProject(context("12", "34", "project-a")))
                .thenThrow(ProjectScopeException.unauthorized("IAM rejected the access token."));

        assertThatThrownBy(() -> service.getAuthorizedNamespace(request))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void rejectsIncompleteWorkspaceOrProjectHeaders() {
        MockHttpServletRequest missingWorkspace = authorizedRequest("12", "34", "project-a");
        missingWorkspace.removeHeader(ProjectScopeAuthorizationService.WORKSPACE_ID_HEADER);
        MockHttpServletRequest missingProject = authorizedRequest("12", "34", "project-a");
        missingProject.removeHeader(ProjectScopeAuthorizationService.PROJECT_ID_HEADER);
        MockHttpServletRequest missingNamespace = authorizedRequest("12", "34", "project-a");
        missingNamespace.removeHeader(ProjectScopeAuthorizationService.NAMESPACE_ID_HEADER);

        assertThatThrownBy(() -> service.getAuthorizedNamespace(missingWorkspace))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.getAuthorizedNamespace(missingProject))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.getAuthorizedNamespace(missingNamespace))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void hidesApplicationStatusThatBelongsToAnotherNamespace() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");
        when(iamClient.isAssignedProject(context("12", "34", "project-a"))).thenReturn(true);
        when(applicationStatusRepository.findById(100L)).thenReturn(Optional.of(
                ApplicationStatus.builder().id(100L).namespace("project-b").build()));

        assertThatThrownBy(() -> service.authorizeApplicationStatus(request, 100L))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void acceptsDeploymentThatBelongsToAuthorizedNamespace() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");
        when(iamClient.isAssignedProject(context("12", "34", "project-a"))).thenReturn(true);
        when(deploymentHistoryRepository.findById(200L)).thenReturn(Optional.of(
                DeploymentHistory.builder().id(200L).namespace("project-a").build()));

        service.authorizeDeployment(request, 200L);

        verify(deploymentHistoryRepository).findById(200L);
    }

    @Test
    void explicitLocalDisablePreservesStandaloneRequestsWithoutHeaders() {
        settings.setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(service.authorizeNamespace(request, "local-ns")).isEqualTo("local-ns");
        assertThat(service.getAuthorizedNamespace(request)).isEmpty();
        service.authorizeApplicationStatus(request, 100L);
        service.authorizeDeployment(request, 200L);

        verify(iamClient, never()).isAssignedProject(org.mockito.ArgumentMatchers.any());
        verify(applicationStatusRepository, never()).findById(100L);
        verify(deploymentHistoryRepository, never()).findById(200L);
    }

    @Test
    void blankNamespaceIsRejectedInBothSecureAndLocalModes() {
        MockHttpServletRequest request = authorizedRequest("12", "34", "project-a");

        assertThatThrownBy(() -> service.authorizeNamespace(request, "  "))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        settings.setEnabled(false);
        assertThatThrownBy(() -> service.authorizeNamespace(new MockHttpServletRequest(), null))
                .isInstanceOfSatisfying(ProjectScopeException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private MockHttpServletRequest authorizedRequest(String workspaceId, String projectId, String namespace) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        request.addHeader(ProjectScopeAuthorizationService.WORKSPACE_ID_HEADER, workspaceId);
        request.addHeader(ProjectScopeAuthorizationService.PROJECT_ID_HEADER, projectId);
        request.addHeader(ProjectScopeAuthorizationService.NAMESPACE_ID_HEADER, namespace);
        return request;
    }

    private ProjectScopeContext context(String workspaceId, String projectId, String namespace) {
        return new ProjectScopeContext("Bearer valid-token", workspaceId, projectId, namespace);
    }
}
