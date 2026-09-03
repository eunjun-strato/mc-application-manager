package kr.co.mcmp.softwarecatalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStorageListObjectsResponse;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStoragePresignedUrlResponse;
import kr.co.mcmp.softwarecatalog.CatalogRepository;
import kr.co.mcmp.softwarecatalog.Ref.CatalogRefEntity;
import kr.co.mcmp.softwarecatalog.SoftwareCatalog;
import kr.co.mcmp.softwarecatalog.application.constants.ObjectStorageAccessMode;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageConfiguration;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageSelection;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageSmokeTestRequest;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageSmokeTestResponse;
import kr.co.mcmp.softwarecatalog.application.service.ObjectStorageAccessGrantService.ResolvedSelection;

@ExtendWith(MockitoExtension.class)
class ObjectStorageSmokeTestServiceTest {

    @Mock
    private CatalogRepository catalogRepository;

    @Mock
    private CbtumblebugRestApi cbtumblebugRestApi;

    @Mock
    private ObjectStorageAccessGrantService grantService;

    private ObjectStorageSmokeTestService service;

    @BeforeEach
    void setUp() {
        service = new ObjectStorageSmokeTestService(catalogRepository, cbtumblebugRestApi, grantService);
    }

    @Test
    void validatesRegisteredObjectStorageForVmWithoutCspCredentials() {
        SoftwareCatalog catalog = SoftwareCatalog.builder()
                .id(11L)
                .catalogRefs(List.of(CatalogRefEntity.builder()
                        .refValue("object-storage")
                        .refType("CAPABILITY")
                        .build()))
                .build();
        ObjectStorageConfiguration configuration = ObjectStorageConfiguration.builder()
                .enabled(true)
                .storages(List.of(ObjectStorageSelection.builder()
                        .objectStorageId("bucket-1")
                        .alias("data")
                        .accessMode("READ_ONLY")
                        .build()))
                .build();

        when(catalogRepository.findByIdWithCatalogRefs(11L)).thenReturn(Optional.of(catalog));
        when(grantService.resolveSelections("ns", configuration)).thenReturn(List.of(
                new ResolvedSelection(
                        "bucket-1", "data", "", ObjectStorageAccessMode.READ_ONLY, "aws", "admin")));
        when(cbtumblebugRestApi.listObjectStorageObjects("ns", "bucket-1", "admin"))
                .thenReturn(ObjectStorageListObjectsResponse.builder().build());
        when(cbtumblebugRestApi.generateObjectStoragePresignedUrl(
                "ns", "bucket-1", ".mcmp-presigned-check", "download", 60, "admin"))
                .thenReturn(ObjectStoragePresignedUrlResponse.builder()
                        .presignedUrl("https://storage.example/check")
                        .build());

        ObjectStorageSmokeTestRequest request = ObjectStorageSmokeTestRequest.builder()
                .namespace("ns")
                .mciId("mci")
                .vmId("vm")
                .catalogId(11L)
                .objectStorage(configuration)
                .build();

        ObjectStorageSmokeTestResponse response = service.runSmokeTest(request);

        assertThat(response.getDetectedProvider()).isEqualTo("aws");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getBackendType()).isEqualTo("tumblebug-presigned-url");
        assertThat(response.getChecks()).extracting("name")
                .containsExactly(
                        "catalogCapability",
                        "selectionValidation",
                        "listObjects:data",
                        "presignedUrl:data");
        verify(cbtumblebugRestApi).listObjectStorageObjects("ns", "bucket-1", "admin");
    }
}
