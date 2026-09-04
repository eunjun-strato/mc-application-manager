package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ObjectStorageTunnelRuntimeTest {
    @Test void usesPinnedHostKeyAndRemoteUnixSocketWithoutPublicPort() {
        var args=ObjectStorageTunnelRuntime.sshArguments("ssh","a".repeat(32),"127.0.0.1",2222,"ubuntu",Path.of("private dir"),23456);
        assertThat(args).contains("StrictHostKeyChecking=yes","IdentitiesOnly=yes","ExitOnForwardFailure=yes","StreamLocalBindMask=0177");
        assertThat(args).contains("/tmp/mcmp-os-"+"a".repeat(32)+"/gateway.sock:127.0.0.1:23456");
        assertThat(args).doesNotContain("0.0.0.0","StrictHostKeyChecking=no","-g");
    }
    @Test void rejectsSshArgumentInjection() {
        assertThatThrownBy(() -> ObjectStorageTunnelRuntime.sshArguments("ssh","a".repeat(32),"-oProxyCommand=bad",22,"ubuntu",Path.of("."),12345))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
