package kr.co.mcmp.softwarecatalog.application.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import kr.co.mcmp.response.ResponseWrapper;
import kr.co.mcmp.softwarecatalog.application.dto.ApplicationStatusDto;
import kr.co.mcmp.softwarecatalog.application.model.ApplicationStatus;
import kr.co.mcmp.softwarecatalog.application.service.ApplicationOrchestrationService;
import kr.co.mcmp.softwarecatalog.application.service.ApplicationService;
import kr.co.mcmp.security.project.ProjectScopeAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 애플리케이션 상태 관련 API 컨트롤러
 */
@RestController
@RequestMapping("/api/applications/status")
@RequiredArgsConstructor
@Slf4j
public class ApplicationStatusController {

    private final ApplicationService applicationService;
    private final ApplicationOrchestrationService applicationOrchestrationService;
    private final ProjectScopeAuthorizationService projectScopeAuthorizationService;

    @Operation(summary = "Get all application status", description = "Retrieve all application statuses.")
    @GetMapping("/all")
    public ResponseEntity<ResponseWrapper<List<ApplicationStatus>>> getAllApplicationStatus(
            HttpServletRequest httpRequest) {
        String namespace = projectScopeAuthorizationService.getAuthorizedNamespace(httpRequest);
        List<ApplicationStatus> result = namespace.isBlank()
                ? applicationService.getAllApplicationStatus()
                : applicationService.getAllApplicationStatus(namespace);
        return ResponseEntity.ok(new ResponseWrapper<>(result));
    }
    
    @Operation(summary = "Get application error logs", description = "Retrieve error logs for a specific application status.")
    @GetMapping("/error-logs/{applicationStatusId}")
    public ResponseEntity<ResponseWrapper<List<String>>> getApplicationErrorLogs(
            @Parameter(description = "Application status ID to get error logs for", required = true, example = "789") @PathVariable Long applicationStatusId,
            HttpServletRequest httpRequest) {
        projectScopeAuthorizationService.authorizeApplicationStatus(httpRequest, applicationStatusId);
        List<String> result = applicationService.getApplicationErrorLogs(applicationStatusId);
        return ResponseEntity.ok(new ResponseWrapper<>(result));
    }
    
    @Operation(summary = "Get application groups", description = "Retrieve application groups.")
    @GetMapping("/groups")
    public ResponseEntity<ResponseWrapper<List<ApplicationStatusDto>>> getApplicationGroups(
            @RequestParam(required = false) String namespace,
            HttpServletRequest httpRequest) {
        String scopedNamespace = namespace == null || namespace.isBlank()
                ? projectScopeAuthorizationService.getAuthorizedNamespace(httpRequest)
                : projectScopeAuthorizationService.authorizeNamespace(httpRequest, namespace);
        List<ApplicationStatusDto> list = scopedNamespace.isBlank()
                ? applicationOrchestrationService.getApplicationGroups()
                : applicationOrchestrationService.getApplicationGroups(scopedNamespace);
        return ResponseEntity.ok(new ResponseWrapper<>(list));
    }
    
    @Operation(summary = "Get latest application status", description = "Retrieve latest application status for a specific user.")
    @GetMapping("/latest")
    public ResponseEntity<ResponseWrapper<ApplicationStatusDto>> getLatestApplicationStatus(
            @Parameter(description = "Username filter (optional)", example = "admin") @RequestParam(required = false) String username,
            HttpServletRequest httpRequest) {
        String namespace = projectScopeAuthorizationService.getAuthorizedNamespace(httpRequest);
        ApplicationStatusDto status = namespace.isBlank()
                ? applicationOrchestrationService.getLatestApplicationStatus(username)
                : applicationOrchestrationService.getLatestApplicationStatus(username, namespace);
        return ResponseEntity.ok(new ResponseWrapper<>(status));
    }
}
