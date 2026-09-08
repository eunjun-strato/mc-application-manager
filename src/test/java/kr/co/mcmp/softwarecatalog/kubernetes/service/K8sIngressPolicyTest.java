package kr.co.mcmp.softwarecatalog.kubernetes.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import kr.co.mcmp.softwarecatalog.application.dto.*;
import kr.co.mcmp.softwarecatalog.application.model.DeploymentHistory;
import kr.co.mcmp.softwarecatalog.application.repository.DeploymentHistoryRepository;
import kr.co.mcmp.softwarecatalog.application.constants.ActionType;

class K8sIngressPolicyTest {
    static final String CIDR = "203.0.113.8/32";
    DeploymentConfigDTO config() {
        return DeploymentConfigDTO.builder().ingressEnabled(true).ingressClass("nginx")
                .ingressHost("app.test.example").ingressPath("/").build();
    }

    @Test void requiresCidrForEveryIngressApplication() {
        var r = DeploymentRequest.builder().build();
        assertThatThrownBy(() -> K8sIngressPolicy.validate(r, config())).isInstanceOf(RuntimeException.class);
        r.setServicePortCidr(CIDR);
        assertThat(K8sIngressPolicy.validate(r, config())).isEqualTo(CIDR);
        var c=config(); c.setIngressClass("traefik");
        assertThatThrownBy(() -> K8sIngressPolicy.validate(r,c)).hasMessageContaining("nginx");
    }

    @Test void privateAppsDoNotOpenFirewall() {
        var c=config(); c.setIngressEnabled(false);
        var r=DeploymentRequest.builder().build();
        assertThat(K8sIngressPolicy.validate(r,c)).isNull();
        r.setOpenServicePort(true);
        assertThatThrownBy(() -> K8sIngressPolicy.validate(r,c)).hasMessageContaining("Enable Ingress");
    }

    @ParameterizedTest @ValueSource(strings={"grafana", "prometheus", "rclone", "loki", "nginx"})
    void setsPolicyInNativeChartValues(String chart) {
        var values=new HashMap<String,String>(); values.put("ingress.enabled","true");
        var file=new HashMap<String,Object>();
        K8sIngressPolicy.configureValues(chart,values,file,config(),CIDR);
        String root=switch(chart){case "prometheus" -> "server.ingress"; case "rclone" -> "ingress.main"; case "loki" -> "gateway.ingress"; default -> "ingress";};
        Object current=file;
        for(String part:root.split("\\."))current=((Map<?,?>)current).get(part);
        var ingress=(Map<?,?>)current;
        assertThat(ingress.get("enabled")).isEqualTo(true);
        assertThat(((Map<?,?>)ingress.get("annotations")).get(K8sIngressAccessService.CIDR_ANNOTATION)).isEqualTo(CIDR);
        assertThat(values).doesNotContainKey("ingress.enabled");
    }

    String ingress(String cidr) {
        return """
                apiVersion: networking.k8s.io/v1
                kind: Ingress
                metadata:
                  name: application
                  annotations:
                    nginx.ingress.kubernetes.io/whitelist-source-range: '%s'
                spec:
                  ingressClassName: nginx
                """.formatted(cidr);
    }

    @Test void refusesMissingOrUnprotectedRenderedIngress() {
        assertThatCode(() -> K8sIngressPolicy.verifyManifest(ingress(CIDR),CIDR)).doesNotThrowAnyException();
        assertThatThrownBy(() -> K8sIngressPolicy.verifyManifest("kind: Deployment",CIDR)).hasMessageContaining("did not render an Ingress");
        assertThatThrownBy(() -> K8sIngressPolicy.verifyManifest(ingress("0.0.0.0/0"),CIDR)).hasMessageContaining("CIDR restriction");
        assertThatThrownBy(() -> K8sIngressPolicy.verifyManifest(ingress(CIDR)+"\n---\n"+ingress(""),CIDR)).hasMessageContaining("CIDR restriction");
    }

    @Test void verifiesTheHostnameUsedByTheHostsFile() {
        String manifest=ingress(CIDR)+"  rules:\n    - host: app.test.example\n";
        assertThatCode(() -> K8sIngressPolicy.verifyManifest(manifest,CIDR,"app.test.example")).doesNotThrowAnyException();
        assertThatThrownBy(() -> K8sIngressPolicy.verifyManifest(manifest,CIDR,"other.test.example")).hasMessageContaining("hostname");
    }

    @ParameterizedTest @ValueSource(strings={"NodePort","LoadBalancer"})
    void refusesServicesBypassingIngress(String type) {
        assertThatThrownBy(() -> K8sIngressPolicy.verifyManifest(ingress(CIDR)+"\n---\nkind: Service\nspec:\n  type: "+type,CIDR))
                .hasMessageContaining("ClusterIP");
    }

    @Test void uninstallReleasesOnlyTheRemovedReleaseAndDelegatesSharedOwnership() {
        var histories=mock(DeploymentHistoryRepository.class);
        var access=mock(K8sIngressAccessService.class);
        var service=new KubernetesOperationService(null,null,access,histories);
        var removed=DeploymentHistory.builder().id(41L).releaseName("grafana-a").build();
        var retained=DeploymentHistory.builder().id(42L).releaseName("grafana-b").build();
        when(histories.findByCatalogIdAndClusterNameAndNamespaceAndActionTypeOrderByExecutedAtDesc(7L,"cluster-a","default",ActionType.INSTALL))
                .thenReturn(List.of(removed,retained));
        service.releaseIngressRules("default","cluster-a",7L,"grafana-a");
        verify(access).release(41L); verifyNoMoreInteractions(access);
        assertThat(removed.getStatus()).isEqualTo("UNINSTALLED");
        assertThat(retained.getStatus()).isNull();
    }
}
