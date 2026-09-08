package kr.co.mcmp.softwarecatalog.kubernetes.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HelmChartServiceRollbackTest {
    @Test void removesTheExactReleaseAndDeletesTemporaryCredentials() throws Exception {
        var helm = mock(HelmChartService.class, CALLS_REAL_METHODS);
        doReturn("apiVersion: v1\n").when(helm).getKubeconfigForCluster("default", "azure");
        doNothing().when(helm).runHelmUninstallCli(anyString(), anyString(), any(Path.class));
        helm.uninstallRelease("default", "azure", "rclone-20260908060923-4ec11a19");
        var config = ArgumentCaptor.forClass(Path.class);
        verify(helm).runHelmUninstallCli(eq("rclone-20260908060923-4ec11a19"), eq("default"), config.capture());
        assertThat(Files.exists(config.getValue())).isFalse();
    }

    @Test void rejectsMissingReleaseWithoutFallingBackToChartName() throws Exception {
        var helm = mock(HelmChartService.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> helm.uninstallRelease("default", "azure", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(helm, never()).getKubeconfigForCluster(anyString(), anyString());
    }

    @Test void preservesUninstallFailureAndStillDeletesTemporaryCredentials() throws Exception {
        var helm = mock(HelmChartService.class, CALLS_REAL_METHODS);
        doReturn("apiVersion: v1\n").when(helm).getKubeconfigForCluster("default", "azure");
        doThrow(new IllegalStateException("API unavailable")).when(helm)
                .runHelmUninstallCli(anyString(), anyString(), any(Path.class));
        assertThatThrownBy(() -> helm.uninstallRelease("default", "azure", "rclone-exact"))
                .hasRootCauseMessage("API unavailable");
        var config = ArgumentCaptor.forClass(Path.class);
        verify(helm).runHelmUninstallCli(eq("rclone-exact"), eq("default"), config.capture());
        assertThat(Files.exists(config.getValue())).isFalse();
    }
}
