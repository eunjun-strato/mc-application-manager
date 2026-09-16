package kr.co.mcmp.softwarecatalog.kubernetes.service;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import kr.co.mcmp.softwarecatalog.application.constants.DeploymentType;
import kr.co.mcmp.softwarecatalog.application.model.DeploymentHistory;
import kr.co.mcmp.softwarecatalog.kubernetes.config.KubernetesClientFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngressAddressServiceTest {
    private final KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    private final KubernetesClientFactory factory = mock(KubernetesClientFactory.class);
    private final IngressAddressService service = new IngressAddressService(factory, mock(kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi.class));

    @SuppressWarnings("unchecked")
    IngressAddressServiceTest() {
        MixedOperation<Pod, PodList, PodResource> podOperation = mock(MixedOperation.class);
        when(client.pods()).thenReturn(podOperation);
        when(podOperation.inNamespace("default")).thenReturn(podOperation);
        when(podOperation.withLabel(anyString(), anyString())).thenReturn(podOperation);
        MixedOperation<io.fabric8.kubernetes.api.model.Service, ServiceList, ServiceResource<io.fabric8.kubernetes.api.model.Service>> serviceOperation = mock(MixedOperation.class);
        when(client.services()).thenReturn(serviceOperation);
        when(serviceOperation.withLabel(anyString(), anyString())).thenReturn(serviceOperation);
        when(serviceOperation.inNamespace("default")).thenReturn(serviceOperation);
        when(serviceOperation.inNamespace("kube-system")).thenReturn(serviceOperation);
        when(serviceOperation.withName(anyString())).thenReturn(mock(ServiceResource.class));
        MixedOperation<Node, NodeList, Resource<Node>> nodeOperation = mock(MixedOperation.class);
        when(client.nodes()).thenReturn(nodeOperation);
        when(nodeOperation.withName(anyString())).thenReturn(mock(Resource.class));
        clearInvocations(client);
    }

    private void pods(Pod... pods) {
        when(client.pods().inNamespace("default")
                .withLabel("app.kubernetes.io/instance", "nginx-ingress-ns")
                .withLabel("app.kubernetes.io/component", "controller").list())
                .thenReturn(new PodListBuilder().withItems(pods).build());
    }

    private Pod pod(String node, String phase) {
        return new PodBuilder().withNewMetadata().endMetadata()
                .withNewSpec().withNodeName(node).endSpec()
                .withNewStatus().withPhase(phase).endStatus().build();
    }

    @Test void onlyRunningControllerNodesAreUsedAndAddressesAreDeduplicated() {
        pods(pod("worker", "Running"), pod("worker", "Running"), pod("pending", "Pending"));
        when(client.nodes().withName("worker").get()).thenReturn(new NodeBuilder().withNewStatus()
                .addNewAddress().withType("InternalIP").withAddress("10.0.0.1").endAddress()
                .addNewAddress().withType("ExternalIP").withAddress("54.1.2.3").endAddress().endStatus().build());
        assertEquals(List.of("54.1.2.3"), service.resolve(client, "ns", "AWS", "nginx"));
        verify(client.nodes().withName("worker"), times(1)).get();
    }

    @Test void privateOnlyWorkerReturnsNoAddress() {
        pods(pod("worker", "Running"));
        when(client.nodes().withName("worker").get()).thenReturn(new NodeBuilder().withNewStatus()
                .addNewAddress().withType("InternalIP").withAddress("10.0.0.1").endAddress().endStatus().build());
        assertEquals(List.of(), service.resolve(client, "ns", "ALIBABA", "nginx"));
        assertEquals(List.of(), service.resolve(client, "ns", "NHN", "nginx"));
    }

    @Test void ibmUsesManagedPublicLoadBalancerInsteadOfWorker() {
        when(client.services().inNamespace("kube-system").list())
                .thenReturn(new ServiceListBuilder().withItems(new ServiceBuilder()
                        .withNewMetadata().withName("public-alb1").addToLabels("app.kubernetes.io/part-of", "managed-ingress").endMetadata()
                        .withNewSpec().withType("LoadBalancer").endSpec().withNewStatus().withNewLoadBalancer()
                        .addNewIngress().withIp("161.1.2.3").endIngress()
                        .endLoadBalancer().endStatus().build()).build());
        assertEquals(List.of("161.1.2.3"), service.resolve(client, "ns", "IBM", "public-iks-k8s-nginx"));
        verify(client, never()).nodes();
    }

    @Test void pendingLoadBalancerAndMissingControllerReturnNoAddress() {
        when(client.services().inNamespace("kube-system").list()).thenReturn(new ServiceListBuilder().build());
        assertTrue(service.resolve(client, "ns", "IBM", "public-iks-k8s-nginx").isEmpty());
        pods();
        assertTrue(service.resolve(client, "ns", "AWS", "nginx").isEmpty());
    }

    @Test void dnsAddressesExcludePrivateAndSharedAddresses() throws Exception {
        var resolving = spy(service);
        doReturn(new java.net.InetAddress[]{java.net.InetAddress.getByName("161.1.2.3"),
                java.net.InetAddress.getByName("10.1.2.3"), java.net.InetAddress.getByName("100.64.0.1")})
                .when(resolving).lookup("alb.example.test");
        var addresses = new java.util.TreeSet<String>();
        resolving.addPublicAddresses(addresses, "alb.example.test");
        assertEquals(java.util.Set.of("161.1.2.3"), addresses);
        assertTrue(service.resolve(client, "ns", "IBM", "private-iks-k8s-nginx").isEmpty());
    }

    @Test void disabledIngressDoesNotConnectAndConnectionFailureIsContained() {
        DeploymentHistory history = new DeploymentHistory();
        history.setDeploymentType(DeploymentType.K8S);
        history.setNamespace("ns");
        history.setClusterName("cluster");
        assertTrue(service.getPublicIps(history, false, "nginx").isEmpty());
        verifyNoInteractions(factory);
        when(factory.getClient("ns", "cluster")).thenThrow(new IllegalStateException("unavailable"));
        assertTrue(service.getPublicIps(history, true, "nginx").isEmpty());
    }

    @Test void workerAccessUsesNodePortAndSelectsTheRouteProtocol() {
        when(client.services().inNamespace("default").withLabel("app.kubernetes.io/instance", "nginx-ingress-ns")
                .withLabel("app.kubernetes.io/component", "controller").list())
                .thenReturn(new ServiceListBuilder().withItems(new ServiceBuilder().withNewMetadata().withName("controller").endMetadata()
                        .withNewSpec().withType("NodePort")
                        .addNewPort().withName("http").withPort(80).withNodePort(30880).endPort()
                        .addNewPort().withName("https").withPort(443).withNodePort(30443).endPort()
                        .addNewPort().withName("metrics").withPort(10254).withNodePort(30254).endPort()
                        .endSpec().build()).build());
        assertEquals(List.of(30880), service.resolvePorts(client, "ns", "AWS", "nginx", false));
        assertEquals(List.of(30443), service.resolvePorts(client, "ns", "AWS", "nginx", true));
    }

    @Test void ibmAccessUsesLoadBalancerPortNotNodePortAndDeduplicates() {
        var alb = new ServiceBuilder().withNewMetadata().withName("public-alb1")
                .addToLabels("app.kubernetes.io/part-of", "managed-ingress").endMetadata()
                .withNewSpec().withType("LoadBalancer")
                .addNewPort().withName("http").withPort(80).withNodePort(31080).endPort()
                .addNewPort().withName("https").withPort(443).withNodePort(31443).endPort().endSpec().build();
        when(client.services().inNamespace("kube-system").list()).thenReturn(new ServiceListBuilder()
                .withItems(alb, new ServiceBuilder(alb).editMetadata().withName("public-alb2").endMetadata().build()).build());
        assertEquals(List.of(80), service.resolvePorts(client, "ns", "IBM", "public-iks-k8s-nginx", false));
        assertEquals(List.of(443), service.resolvePorts(client, "ns", "IBM", "public-iks-k8s-nginx", true));
        assertEquals(List.of(), service.resolvePorts(client, "ns", "IBM", "private-iks-k8s-nginx", false));
    }

    @Test void missingNodePortDoesNotExposeInternalServicePort() {
        when(client.services().inNamespace("default").withLabel(anyString(), anyString()).withLabel(anyString(), anyString()).list())
                .thenReturn(new ServiceListBuilder().withItems(new ServiceBuilder().withNewMetadata().withName("controller").endMetadata()
                        .withNewSpec().withType("ClusterIP").addNewPort().withName("http").withPort(80).endPort().endSpec().build()).build());
        assertTrue(service.resolvePorts(client, "ns", "NHN", "nginx", false).isEmpty());
    }

    @Test void endpointUsesOneClientAndDisabledIngressSkipsAllLookups() {
        DeploymentHistory history = new DeploymentHistory();
        history.setDeploymentType(DeploymentType.K8S); history.setNamespace("ns");
        history.setClusterName("cluster"); history.setCloudProvider("AWS");
        assertEquals(new IngressAddressService.Endpoint(List.of(), List.of()), service.getEndpoint(history, false, "nginx", false));
        verifyNoInteractions(factory);
        when(factory.getClient("ns", "cluster")).thenReturn(client);
        pods();
        when(client.services().inNamespace("default").withLabel(anyString(), anyString()).withLabel(anyString(), anyString()).list())
                .thenReturn(new ServiceListBuilder().build());
        assertEquals(new IngressAddressService.Endpoint(List.of(), List.of()), service.getEndpoint(history, true, "nginx", false));
        verify(factory, times(1)).getClient("ns", "cluster");
    }
}
