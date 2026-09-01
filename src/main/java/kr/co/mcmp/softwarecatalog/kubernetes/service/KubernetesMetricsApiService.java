package kr.co.mcmp.softwarecatalog.kubernetes.service;

import org.springframework.stereotype.Service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

/**
 * Probes the Kubernetes resource metrics API without assuming that a
 * metrics-server Deployment is visible or managed by this application.
 */
@Service
public class KubernetesMetricsApiService {

    private static final ResourceDefinitionContext NODE_METRICS_CONTEXT =
            new ResourceDefinitionContext.Builder()
                    .withGroup("metrics.k8s.io")
                    .withVersion("v1beta1")
                    .withKind("NodeMetrics")
                    .withPlural("nodes")
                    .withNamespaced(false)
                    .build();

    public enum Availability {
        AVAILABLE,
        NOT_REGISTERED,
        TEMPORARILY_UNAVAILABLE
    }

    public Availability check(KubernetesClient client) {
        try {
            client.genericKubernetesResources(NODE_METRICS_CONTEXT).list();
            return Availability.AVAILABLE;
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                return Availability.NOT_REGISTERED;
            }
            if (e.getCode() == 503) {
                return Availability.TEMPORARILY_UNAVAILABLE;
            }

            // Authentication, authorization, networking, and unexpected server
            // failures must not be mistaken for a missing metrics-server.
            throw e;
        }
    }
}
