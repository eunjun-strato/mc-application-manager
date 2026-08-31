package kr.co.mcmp.softwarecatalog.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import kr.co.mcmp.oss.dto.OssDto;
import kr.co.mcmp.softwarecatalog.CatalogRepository;
import kr.co.mcmp.softwarecatalog.application.config.NexusConfig;
import kr.co.mcmp.softwarecatalog.application.dto.HelmChartRegistrationRequest;
import kr.co.mcmp.softwarecatalog.application.repository.HelmChartRepository;
import kr.co.mcmp.softwarecatalog.application.repository.PackageInfoRepository;
import kr.co.mcmp.softwarecatalog.application.service.ArtifactHubIntegrationService;
import kr.co.mcmp.softwarecatalog.application.service.NexusIntegrationService;
import kr.co.mcmp.softwarecatalog.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class HelmChartIntegrationServiceImplTest {

    @Mock
    private ArtifactHubIntegrationService artifactHubIntegrationService;

    @Mock
    private NexusIntegrationService nexusIntegrationService;

    @Mock
    private HelmChartRepository helmChartRepository;

    @Mock
    private PackageInfoRepository packageInfoRepository;

    @Mock
    private CatalogRepository catalogRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NexusConfig nexusConfig;

    @InjectMocks
    private HelmChartIntegrationServiceImpl service;

    @Test
    void usesNexusUrlOnlyWhenTheExactChartExists() {
        HelmChartRegistrationRequest request = request("5.8.56", "5.8.56", "https://charts.jenkins.io");
        when(nexusIntegrationService.getRepositoryNameByFormat("helm")).thenReturn("helm-hosted");
        when(nexusIntegrationService.checkHelmChartExistsInNexus("helm-hosted", "jenkins", "5.8.56"))
                .thenReturn(true);
        when(nexusIntegrationService.getNexusInfoFromDB()).thenReturn(
                OssDto.builder().ossUrl("http://nexus.test:8081").build());

        String resolved = ReflectionTestUtils.invokeMethod(service, "resolveChartRepositoryUrl", request);

        assertThat(resolved).isEqualTo("http://nexus.test:8081/repository/helm-hosted");
    }

    @Test
    void fallsBackToExternalRepositoryWhenTheExactChartIsMissing() {
        HelmChartRegistrationRequest request = request("5.8.56", "5.8.56", "https://charts.jenkins.io");
        when(nexusIntegrationService.getRepositoryNameByFormat("helm")).thenReturn("helm-hosted");
        when(nexusIntegrationService.checkHelmChartExistsInNexus("helm-hosted", "jenkins", "5.8.56"))
                .thenReturn(false);

        String resolved = ReflectionTestUtils.invokeMethod(service, "resolveChartRepositoryUrl", request);

        assertThat(resolved).isEqualTo("https://charts.jenkins.io");
        verify(nexusIntegrationService, never()).getNexusInfoFromDB();
    }

    @Test
    void usesTagAsChartVersionWhenVersionIsBlank() {
        HelmChartRegistrationRequest request = request(" ", "5.8.56", "https://charts.jenkins.io");
        when(nexusIntegrationService.getRepositoryNameByFormat("helm")).thenReturn("helm-hosted");
        when(nexusIntegrationService.checkHelmChartExistsInNexus("helm-hosted", "jenkins", "5.8.56"))
                .thenReturn(true);
        when(nexusIntegrationService.getNexusInfoFromDB()).thenReturn(
                OssDto.builder().ossUrl("http://nexus.test:8081").build());

        String resolved = ReflectionTestUtils.invokeMethod(service, "resolveChartRepositoryUrl", request);

        assertThat(resolved).isEqualTo("http://nexus.test:8081/repository/helm-hosted");
        verify(nexusIntegrationService)
                .checkHelmChartExistsInNexus("helm-hosted", "jenkins", "5.8.56");
    }

    @Test
    void fallsBackToExternalRepositoryWhenNexusLookupFails() {
        HelmChartRegistrationRequest request = request("5.8.56", "5.8.56", "https://charts.jenkins.io");
        when(nexusIntegrationService.getRepositoryNameByFormat("helm"))
                .thenThrow(new RuntimeException("Nexus unavailable"));

        String resolved = ReflectionTestUtils.invokeMethod(service, "resolveChartRepositoryUrl", request);

        assertThat(resolved).isEqualTo("https://charts.jenkins.io");
        verify(nexusIntegrationService, never())
                .checkHelmChartExistsInNexus("helm-hosted", "jenkins", "5.8.56");
    }

    @Test
    void returnsEmptyUrlWhenNeitherNexusChartNorExternalRepositoryExists() {
        HelmChartRegistrationRequest request = request("5.8.56", "5.8.56", " ");
        when(nexusIntegrationService.getRepositoryNameByFormat("helm")).thenReturn("helm-hosted");
        when(nexusIntegrationService.checkHelmChartExistsInNexus("helm-hosted", "jenkins", "5.8.56"))
                .thenReturn(false);

        String resolved = ReflectionTestUtils.invokeMethod(service, "resolveChartRepositoryUrl", request);

        assertThat(resolved).isEmpty();
    }

    private HelmChartRegistrationRequest request(String version, String tag, String externalRepositoryUrl) {
        return HelmChartRegistrationRequest.builder()
                .name("jenkins")
                .version(version)
                .tag(tag)
                .repository(HelmChartRegistrationRequest.Repository.builder()
                        .name("jenkins")
                        .url(externalRepositoryUrl)
                        .build())
                .build();
    }
}
