package kr.co.mcmp.ape.cbtumblebug.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStoragePresignedUrlResponse;

@ExtendWith(MockitoExtension.class)
class CbtumblebugRestApiObjectStorageTest {
    @Mock
    private CbtumblebugRestClient restClient;

    private CbtumblebugRestApi api;

    @BeforeEach
    void setUp() {
        api = new CbtumblebugRestApi(restClient);
        ReflectionTestUtils.setField(api, "cbtumblebugUrl", "mc-infra-manager");
        ReflectionTestUtils.setField(api, "cbtumblebugPort", "1323");
        ReflectionTestUtils.setField(api, "cbtumblebugId", "test-user");
        ReflectionTestUtils.setField(api, "cbtumblebugPass", "test-pass");
    }

    @Test
    void encodesNestedObjectKeyAsOnePathSegment() {
        when(restClient.request(anyString(), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok("ready"));
        when(restClient.request(any(URI.class), any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(ObjectStoragePresignedUrlResponse.builder()
                        .method("GET")
                        .presignedUrl("https://storage.example/object")
                        .build()));

        api.generateObjectStoragePresignedUrl(
                "default", "bucket-1", "data/project a.csv", "download", 600, "admin");

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restClient).request(uriCaptor.capture(), any(), any(), any(), any());
        assertThat(uriCaptor.getValue().getRawPath())
                .contains("/object/data%2Fproject%20a.csv/presignedUrl");
        assertThat(uriCaptor.getValue().getQuery()).contains("operation=download", "expires=600");
    }
}
