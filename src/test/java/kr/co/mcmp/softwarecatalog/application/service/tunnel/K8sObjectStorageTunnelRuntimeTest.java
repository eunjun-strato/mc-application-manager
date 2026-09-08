package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class K8sObjectStorageTunnelRuntimeTest {
    @Test void reverseForwardUsesOnlyPodLoopbackAndPinsHostKey() {
        var args=K8sObjectStorageTunnelRuntime.sshArguments("ssh",41L,Path.of("test-keys"),40123,40124);
        assertThat(args).contains("StrictHostKeyChecking=yes","HostKeyAlias=mcmp-k8s-41","ExitOnForwardFailure=yes",
                "ServerAliveInterval=15","ServerAliveCountMax=3","127.0.0.1:18084:127.0.0.1:40124");
        assertThat(args.get(args.size()-1)).isEqualTo("127.0.0.1");
        assertThat(args).doesNotContain("0.0.0.0", "StrictHostKeyChecking=no");
    }
    @Test void hostKeyDoesNotAcceptInjectedOptions() {
        assertThatThrownBy(()->K8sObjectStorageTunnelRuntime.publicKey("ssh-rsa abc")).isInstanceOf(IllegalArgumentException.class);
        assertThat(K8sObjectStorageTunnelRuntime.publicKey("ssh-ed25519 YWJj comment")).isEqualTo("ssh-ed25519 YWJj");
    }
    @Test void generatedCredentialsAreUniqueAndDoNotIncludeGatewayToken() {
        var runtime=new K8sObjectStorageTunnelRuntime(null,null);
        var first=runtime.credentials("default","mcmp-jupyter-1");
        var second=runtime.credentials("default","mcmp-jupyter-2");
        assertThat(first.getImmutable()).isTrue();
        assertThat(first.getStringData()).containsOnlyKeys("client-key","host-key","host-public","authorized_keys");
        assertThat(first.getStringData().get("client-key")).isNotEqualTo(second.getStringData().get("client-key"));
        assertThat(first.getStringData().get("authorized_keys")).startsWith("restrict,port-forwarding,permitlisten=\"127.0.0.1:18084\" ssh-ed25519 ");
    }
}
