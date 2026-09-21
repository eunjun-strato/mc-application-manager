package kr.co.mcmp.softwarecatalog.application.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.mcmp.security.project.ProjectScopeAuthorizationService;
import kr.co.mcmp.softwarecatalog.application.constants.DeploymentType;
import kr.co.mcmp.softwarecatalog.application.service.DeploymentSubmissionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeploymentSubmissionControllerTest {
    @ParameterizedTest @EnumSource(value=DeploymentType.class,names={"VM","K8S"})
    void bothVmAndK8sSubmitAsyncWithAuthorizedNamespace(DeploymentType type) throws Exception {
        var auth=mock(ProjectScopeAuthorizationService.class);var jobs=mock(DeploymentSubmissionService.class);
        when(auth.authorizeNamespace(any(),eq("default"))).thenReturn("default");
        when(jobs.submit(any(),eq("some-operation"),any())).thenReturn(new DeploymentSubmissionService.Status("some-operation","default","QUEUED","accepted",null));
        var mvc=MockMvcBuilders.standaloneSetup(new DeploymentSubmissionController(auth,jobs,new ObjectMapper())).build();
        mvc.perform(post("/applications/deployment-submissions/"+type.name()).header("Idempotency-Key","some-operation")
                .contentType("application/json").content("{\"namespace\":\"default\",\"catalogId\":11}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("QUEUED"));
        verify(auth).authorizeNamespace(any(),eq("default"));
        verify(jobs).submit(any(),eq("some-operation"),argThat(r->r.getDeploymentType()==type));
    }
}
