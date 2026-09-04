package kr.co.mcmp.softwarecatalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStorageInfo;
import kr.co.mcmp.softwarecatalog.application.constants.ObjectStorageAccessMode;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageConfiguration;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageSelection;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageAccessGrant;
import kr.co.mcmp.softwarecatalog.application.repository.ObjectStorageAccessGrantRepository;
import kr.co.mcmp.softwarecatalog.application.service.ObjectStorageAccessGrantService.IssuedAccess;

@ExtendWith(MockitoExtension.class)
class ObjectStorageAccessGrantServiceTest {
    @Mock
    private ObjectStorageAccessGrantRepository repository;

    @Mock
    private CbtumblebugRestApi cbtumblebugRestApi;

    private ObjectStorageAccessGrantService service;

    @BeforeEach
    void setUp() {
        service = new ObjectStorageAccessGrantService(repository, cbtumblebugRestApi);
    }

    @Test
    void issuesOpaqueTokenAndStoresOnlyItsHash() {
        ObjectStorageInfo.ConnectionConfig connection = ObjectStorageInfo.ConnectionConfig.builder()
                .providerName("aws")
                .credentialHolder("admin")
                .build();
        when(cbtumblebugRestApi.getObjectStorage("default", "bucket-1"))
                .thenReturn(ObjectStorageInfo.builder()
                        .id("bucket-1")
                        .status("Available")
                        .connectionConfig(connection)
                        .build());
        ObjectStorageConfiguration configuration = ObjectStorageConfiguration.builder()
                .enabled(true)
                .storages(List.of(ObjectStorageSelection.builder()
                        .objectStorageId("bucket-1")
                        .alias("analytics")
                        .prefix("data/project-a")
                        .accessMode("READ_WRITE")
                        .build()))
                .build();

        IssuedAccess issued = service.issue(7L, "vm-1", "default", configuration);

        assertThat(issued.token()).startsWith("mcmp_os_");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectStorageAccessGrant>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        ObjectStorageAccessGrant grant = captor.getValue().get(0);
        assertThat(grant.getTokenHash()).hasSize(64).isNotEqualTo(issued.token());
        assertThat(grant.getPrefix()).isEqualTo("data/project-a/");
        assertThat(grant.getAccessMode()).isEqualTo(ObjectStorageAccessMode.READ_WRITE);
    }

    @Test
    void rejectsUploadAndKeysOutsideReadOnlyGrant() {
        ObjectStorageAccessGrant grant = ObjectStorageAccessGrant.builder()
                .prefix("data/project-a/")
                .accessMode(ObjectStorageAccessMode.READ_ONLY)
                .build();

        assertThatThrownBy(() -> service.authorizeObjectKey(grant, "data/project-a/file.csv", true))
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> service.authorizeObjectKey(grant, "data/project-b/file.csv", false))
                .hasMessageContaining("outside");
        assertThat(service.authorizeObjectKey(grant, "data/project-a/file.csv", false))
                .isEqualTo("data/project-a/file.csv");
    }
}
