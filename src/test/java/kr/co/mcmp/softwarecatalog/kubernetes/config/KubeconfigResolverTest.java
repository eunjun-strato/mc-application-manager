package kr.co.mcmp.softwarecatalog.kubernetes.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.K8sClusterDto;

@ExtendWith(MockitoExtension.class)
class KubeconfigResolverTest {

    private static final String NAMESPACE = "default";
    private static final String CLUSTER_NAME = "test-cluster";
    private static final String TOKEN = "test-kubernetes-token";
    private static final String EXEC_KUBECONFIG = """
            apiVersion: v1
            kind: Config
            clusters:
              - name: test-cluster
                cluster:
                  server: https://kubernetes.example.test
            contexts:
              - name: test-context
                context:
                  cluster: test-cluster
                  user: test-user
            current-context: test-context
            users:
              - name: test-user
                user:
                  exec:
                    apiVersion: client.authentication.k8s.io/v1beta1
                    command: sh
                    args: ["-c", "get-token"]
            """;

    @Mock
    private CbtumblebugRestApi cbtumblebugRestApi;

    @Mock
    private KubeConfigProviderFactory providerFactory;

    @Mock
    private KubeConfigProvider provider;

    private KubeconfigResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new KubeconfigResolver(cbtumblebugRestApi, providerFactory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws", "gcp", "ncp", "NCP", "ncloud", "naver-cloud", "ncp-vpc"})
    void injectsTumblebugTokenForSupportedProviders(String providerName) {
        K8sClusterDto cluster = cluster(providerName);
        when(cbtumblebugRestApi.getK8sClusterByName(NAMESPACE, CLUSTER_NAME)).thenReturn(cluster);
        when(providerFactory.getProvider(providerName)).thenReturn(provider);
        when(cbtumblebugRestApi.getK8sClusterKubeconfig(NAMESPACE, CLUSTER_NAME)).thenReturn(EXEC_KUBECONFIG);
        when(cbtumblebugRestApi.getK8sClusterToken(NAMESPACE, CLUSTER_NAME)).thenReturn(TOKEN);

        String resolved = resolver.getKubeconfigYaml(NAMESPACE, CLUSTER_NAME);

        assertThat(resolved)
                .contains("token: " + TOKEN)
                .doesNotContain("exec:")
                .doesNotContain("get-token");
        verify(provider, never()).getOriginalKubeconfigYaml(cluster);
    }

    @Test
    void preservesRawKubeconfigForProvidersNotUsingTumblebugTokenAuth() {
        K8sClusterDto cluster = cluster("ibm");
        when(cbtumblebugRestApi.getK8sClusterByName(NAMESPACE, CLUSTER_NAME)).thenReturn(cluster);
        when(providerFactory.getProvider("ibm")).thenReturn(provider);
        when(provider.getOriginalKubeconfigYaml(cluster)).thenReturn(EXEC_KUBECONFIG);

        assertThat(resolver.getKubeconfigYaml(NAMESPACE, CLUSTER_NAME)).isEqualTo(EXEC_KUBECONFIG);

        verify(cbtumblebugRestApi, never()).getK8sClusterKubeconfig(NAMESPACE, CLUSTER_NAME);
        verify(cbtumblebugRestApi, never()).getK8sClusterToken(NAMESPACE, CLUSTER_NAME);
    }

    @Test
    void failsClosedWhenTumblebugReturnsAnEmptyToken() {
        K8sClusterDto cluster = cluster("ncp");
        when(cbtumblebugRestApi.getK8sClusterByName(NAMESPACE, CLUSTER_NAME)).thenReturn(cluster);
        when(providerFactory.getProvider("ncp")).thenReturn(provider);
        when(cbtumblebugRestApi.getK8sClusterKubeconfig(NAMESPACE, CLUSTER_NAME)).thenReturn(EXEC_KUBECONFIG);
        when(cbtumblebugRestApi.getK8sClusterToken(NAMESPACE, CLUSTER_NAME)).thenReturn("  ");

        assertThatThrownBy(() -> resolver.getKubeconfigYaml(NAMESPACE, CLUSTER_NAME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty Kubernetes token");
    }

    @Test
    void failsClosedWhenKubeconfigHasNoUsers() {
        K8sClusterDto cluster = cluster("ncp");
        when(cbtumblebugRestApi.getK8sClusterByName(NAMESPACE, CLUSTER_NAME)).thenReturn(cluster);
        when(providerFactory.getProvider("ncp")).thenReturn(provider);
        when(cbtumblebugRestApi.getK8sClusterKubeconfig(NAMESPACE, CLUSTER_NAME))
                .thenReturn("apiVersion: v1\nkind: Config\n");
        when(cbtumblebugRestApi.getK8sClusterToken(NAMESPACE, CLUSTER_NAME)).thenReturn(TOKEN);

        assertThatThrownBy(() -> resolver.getKubeconfigYaml(NAMESPACE, CLUSTER_NAME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain users");
    }

    private K8sClusterDto cluster(String providerName) {
        K8sClusterDto cluster = new K8sClusterDto();
        cluster.setName(CLUSTER_NAME);
        K8sClusterDto.ConnectionConfig connectionConfig = new K8sClusterDto.ConnectionConfig();
        connectionConfig.setProviderName(providerName);
        cluster.setConnectionConfig(connectionConfig);
        return cluster;
    }
}
