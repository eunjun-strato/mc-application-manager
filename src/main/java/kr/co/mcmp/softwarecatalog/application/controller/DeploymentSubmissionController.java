package kr.co.mcmp.softwarecatalog.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import kr.co.mcmp.response.ResponseWrapper;
import kr.co.mcmp.security.project.ProjectScopeAuthorizationService;
import kr.co.mcmp.softwarecatalog.application.constants.DeploymentType;
import kr.co.mcmp.softwarecatalog.application.dto.DeploymentRequestDTO;
import kr.co.mcmp.softwarecatalog.application.service.DeploymentSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/applications/deployment-submissions")
@RequiredArgsConstructor
public class DeploymentSubmissionController {
    private final ProjectScopeAuthorizationService authorization;
    private final DeploymentSubmissionService jobs;
    private final ObjectMapper mapper;

    @PostMapping("/{type}")
    public ResponseWrapper<DeploymentSubmissionService.Status> submit(@PathVariable DeploymentType type,
            @RequestHeader("Idempotency-Key") String id, @RequestBody DeploymentRequestDTO request, HttpServletRequest http) {
        if(type!=DeploymentType.VM && type!=DeploymentType.K8S) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only VM and K8S deployments are supported.");
        var scope=scope(http,request.getNamespace());
        request.setDeploymentType(type);
        return new ResponseWrapper<>(jobs.submit(scope,id,request));
    }
    @GetMapping("/{id}")
    public ResponseWrapper<DeploymentSubmissionService.Status> get(@PathVariable String id,@RequestParam String namespace,HttpServletRequest http) {
        return new ResponseWrapper<>(jobs.get(scope(http,namespace),id));
    }
    @PostMapping("/{id}/close-interrupted")
    public ResponseWrapper<DeploymentSubmissionService.Status> closeInterrupted(@PathVariable String id,
            @RequestParam String namespace,HttpServletRequest http) {
        return new ResponseWrapper<>(jobs.closeInterrupted(scope(http,namespace),id));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ResponseWrapper<String>> statusError(ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode())
                .body(new ResponseWrapper<>(ex.getStatusCode().value(), "Deployment request rejected", ex.getReason()));
    }
    private DeploymentSubmissionService.Scope scope(HttpServletRequest http,String namespace) {
        String ns=authorization.authorizeNamespace(http,namespace);
        if(!authorization.isEnabled()) return new DeploymentSubmissionService.Scope("local","local",ns,"local");
        try {
            // authorizeNamespace above validates the bearer against IAM. Only its non-secret subject is retained.
            String bearer=http.getHeader("Authorization").substring(7).trim();
            String owner=mapper.readTree(Base64.getUrlDecoder().decode(bearer.split("\\.")[1])).path("sub").asText();
            if(owner.isBlank()) throw new IllegalArgumentException();
            return new DeploymentSubmissionService.Scope(http.getHeader("X-MCMP-Workspace-ID"),http.getHeader("X-MCMP-Project-ID"),ns,owner);
        } catch(Exception ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"An authenticated user subject is required."); }
    }
}
