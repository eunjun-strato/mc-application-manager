package kr.co.mcmp.softwarecatalog.kubernetes.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.K8sClusterDto;
import kr.co.mcmp.ape.cbtumblebug.dto.VmAccessInfo;
import kr.co.mcmp.softwarecatalog.application.dto.DeploymentRequest;
import kr.co.mcmp.softwarecatalog.application.model.DeploymentHistory;
import kr.co.mcmp.softwarecatalog.application.service.VmSecurityGroupExposureService;

class K8sIngressAccessServiceTest {
    @Test void addsNodePortInsteadOfJupyterInternalPortUsingSharedLedger() {
        var tb=mock(CbtumblebugRestApi.class); var ledger=mock(VmSecurityGroupExposureService.class);
        var resolver=mock(K8sWorkerSecurityGroupResolver.class); var service=new K8sIngressAccessService(tb,ledger,resolver);
        var cluster=new K8sClusterDto(); var network=new K8sClusterDto.Network();
        network.setSecurityGroupIds(List.of("worker-sg")); cluster.setNetwork(network);
        when(tb.getK8sClusterByName("default","cluster-a")).thenReturn(cluster);
        var r=DeploymentRequest.builder().namespace("default").clusterName("cluster-a").openServicePort(true)
                .servicePort(8888).servicePortCidr("203.0.113.8/32").build();
        when(resolver.resolve("default",cluster)).thenReturn("actual-worker-sg"); var h=DeploymentHistory.builder().id(41L).build(); service.open(r,h);
        var captured=ArgumentCaptor.forClass(DeploymentRequest.class);
        verify(ledger).addRestrictedInboundRule(captured.capture(),any(VmAccessInfo.class),same(h));
        assertThat(captured.getValue().getServicePort()).isEqualTo(30880);
        assertThat(captured.getValue().getServicePortCidr()).isEqualTo("203.0.113.8/32");
        service.release(41L); verify(ledger).releaseRestrictedInboundRule(41L);
    }

    @Test void rejectsMissingClusterSecurityGroupWithoutGuessing() {
        var tb=mock(CbtumblebugRestApi.class); var ledger=mock(VmSecurityGroupExposureService.class);
        when(tb.getK8sClusterByName("default","cluster-a")).thenReturn(new K8sClusterDto());
        var resolver=mock(K8sWorkerSecurityGroupResolver.class); var service=new K8sIngressAccessService(tb,ledger,resolver);
        var r=DeploymentRequest.builder().namespace("default").clusterName("cluster-a").openServicePort(true).servicePortCidr("203.0.113.8/32").build();
        when(resolver.resolve(eq("default"),any())).thenThrow(new IllegalArgumentException("worker Security Group missing")); assertThatThrownBy(() -> service.open(r,DeploymentHistory.builder().id(41L).build())).hasMessageContaining("worker Security Group");
        verifyNoInteractions(ledger);
    }

    @Test void leavesFirewallAloneWhenNotRequested() {
        var tb=mock(CbtumblebugRestApi.class); var ledger=mock(VmSecurityGroupExposureService.class);
        new K8sIngressAccessService(tb,ledger,new K8sWorkerSecurityGroupResolver(tb)).open(DeploymentRequest.builder().build(),null);
        verifyNoInteractions(tb,ledger);
    }
}

