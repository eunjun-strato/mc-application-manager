package kr.co.mcmp.softwarecatalog.kubernetes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

@ExtendWith(MockitoExtension.class)
class KubernetesMetricsApiServiceTest {

    @Mock
    private KubernetesClient client;

    @Mock
    private MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> metricsOperation;

    private KubernetesMetricsApiService service;

    @BeforeEach
    void setUp() {
        service = new KubernetesMetricsApiService();
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class)))
                .thenReturn(metricsOperation);
    }

    @Test
    void reportsAvailableWhenMetricsApiRespondsEvenWithNoNodeMetrics() {
        when(metricsOperation.list()).thenReturn(new GenericKubernetesResourceList());

        assertThat(service.check(client))
                .isEqualTo(KubernetesMetricsApiService.Availability.AVAILABLE);
    }

    @Test
    void reportsNotRegisteredForHttp404() {
        when(metricsOperation.list()).thenThrow(kubernetesError(404));

        assertThat(service.check(client))
                .isEqualTo(KubernetesMetricsApiService.Availability.NOT_REGISTERED);
    }

    @Test
    void reportsTemporarilyUnavailableForHttp503() {
        when(metricsOperation.list()).thenThrow(kubernetesError(503));

        assertThat(service.check(client))
                .isEqualTo(KubernetesMetricsApiService.Availability.TEMPORARILY_UNAVAILABLE);
    }

    @Test
    void propagatesAuthenticationFailureInsteadOfTreatingItAsMissingMetricsServer() {
        KubernetesClientException unauthorized = kubernetesError(401);
        when(metricsOperation.list()).thenThrow(unauthorized);

        assertThatThrownBy(() -> service.check(client)).isSameAs(unauthorized);
    }

    @Test
    void propagatesAuthorizationFailureInsteadOfTreatingItAsMissingMetricsServer() {
        KubernetesClientException forbidden = kubernetesError(403);
        when(metricsOperation.list()).thenThrow(forbidden);

        assertThatThrownBy(() -> service.check(client)).isSameAs(forbidden);
    }

    @Test
    void propagatesNetworkFailureInsteadOfInstallingMetricsServer() {
        IllegalStateException connectionFailure = new IllegalStateException("connection refused");
        when(metricsOperation.list()).thenThrow(connectionFailure);

        assertThatThrownBy(() -> service.check(client)).isSameAs(connectionFailure);
    }

    private KubernetesClientException kubernetesError(int statusCode) {
        return new KubernetesClientException("metrics API error", statusCode, null);
    }
}
