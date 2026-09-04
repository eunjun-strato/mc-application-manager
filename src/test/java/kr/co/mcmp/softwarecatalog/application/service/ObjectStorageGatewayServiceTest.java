package kr.co.mcmp.softwarecatalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStoragePresignedUrlResponse;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStoragePresignedUrlRequest;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageAccessGrant;

class ObjectStorageGatewayServiceTest {

    private static final String NCP_URL =
            "https://bucket.KR.object.ncloudstorage.com/sample.csv"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-SignedHeaders=host%3Bx-amz-date";

    private static final String NHN_URL =
            "https://KR1-api-object-storage.nhncloudservice.com/bucket/sample%20data.csv"
                    + "?X-Amz-SignedHeaders=host&X-Amz-Signature=test-only-signature";

    @ParameterizedTest
    @ValueSource(strings = {"nhn", "NHN", " nhn "})
    void preservesNhnSignedAuthorityAndEntireUrl(String provider) {
        ObjectStoragePresignedUrlResponse original = response(NHN_URL, Map.of("x-required", "value"));
        ObjectStoragePresignedUrlResponse adjusted = ObjectStorageGatewayService.preserveSignedHost(provider, original);
        assertThat(adjusted.getRequiredHeaders())
                .containsEntry("Host", "KR1-api-object-storage.nhncloudservice.com")
                .containsEntry("x-required", "value");
        assertThat(adjusted.getPresignedUrl()).isEqualTo(NHN_URL);
        assertThat(adjusted.getMethod()).isEqualTo("GET");
        assertThat(adjusted.getExpires()).isEqualTo(600L);
        assertThat(original.getRequiredHeaders()).doesNotContainKey("Host");
    }

    @ParameterizedTest
    @ValueSource(strings = {"KR1", "KR2", "JP1", "US1", "kr1"})
    void supportsNhnRegionalEndpointsForUploadWithoutRewritingThem(String region) {
        String host = region + "-api-object-storage.nhncloudservice.com";
        ObjectStoragePresignedUrlResponse original = response(
                "https://" + host + "/bucket/object?X-Amz-SignedHeaders=host", null);
        original.setMethod("PUT");
        ObjectStoragePresignedUrlResponse adjusted = ObjectStorageGatewayService.preserveSignedHost("nhn", original);
        assertThat(adjusted.getRequiredHeaders()).containsEntry("Host", host);
        assertThat(adjusted.getPresignedUrl()).isEqualTo(original.getPresignedUrl());
        assertThat(adjusted.getMethod()).isEqualTo("PUT");
    }

    @Test
    void doesNotOverrideNhnProviderHeadersOrUnsignedHosts() {
        ObjectStoragePresignedUrlResponse withHost = response(NHN_URL, Map.of("hOsT", "already-signed"));
        assertThat(ObjectStorageGatewayService.preserveSignedHost("nhn", withHost)).isSameAs(withHost);
        ObjectStoragePresignedUrlResponse unsigned = response(
                NHN_URL.replace("X-Amz-SignedHeaders=host", "X-Amz-SignedHeaders=x-amz-date"), Map.of());
        assertThat(ObjectStorageGatewayService.preserveSignedHost("nhn", unsigned)).isSameAs(unsigned);
    }

    @Test
    void onlyAddsHeadersWhenStorageProviderAndEndpointBothMatch() {
        ObjectStoragePresignedUrlResponse ncp = response(NCP_URL, Map.of());
        ObjectStoragePresignedUrlResponse nhn = response(NHN_URL, Map.of());
        ObjectStoragePresignedUrlResponse lookalike = response(
                NHN_URL.replace(".nhncloudservice.com", ".nhncloudservice.com.evil.example"), Map.of());
        assertThat(ObjectStorageGatewayService.preserveSignedHost("nhn", ncp)).isSameAs(ncp);
        assertThat(ObjectStorageGatewayService.preserveSignedHost("ncp", nhn)).isSameAs(nhn);
        assertThat(ObjectStorageGatewayService.preserveSignedHost("aws", nhn)).isSameAs(nhn);
        assertThat(ObjectStorageGatewayService.preserveSignedHost("nhn", lookalike)).isSameAs(lookalike);
    }

    @Test
    void suppliesOriginalNcpAuthorityAsRequiredHostWithoutChangingSignedUrl() {
        ObjectStoragePresignedUrlResponse original = response(NCP_URL, Map.of("x-required", "value"));

        ObjectStoragePresignedUrlResponse adjusted =
                ObjectStorageGatewayService.preserveSignedHost("NCP", original);

        assertThat(adjusted).isNotSameAs(original);
        assertThat(adjusted.getPresignedUrl()).isEqualTo(NCP_URL);
        assertThat(adjusted.getMethod()).isEqualTo("GET");
        assertThat(adjusted.getExpires()).isEqualTo(600L);
        assertThat(adjusted.getRequiredHeaders())
                .containsEntry("Host", "bucket.KR.object.ncloudstorage.com")
                .containsEntry("x-required", "value");
        assertThat(original.getRequiredHeaders()).doesNotContainKey("Host");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ncp", "nhn"})
    void appliesFixFromGrantedStorageProvider(String provider) {
        String url = "nhn".equals(provider) ? NHN_URL : NCP_URL;
        String expectedHost = "nhn".equals(provider)
                ? "KR1-api-object-storage.nhncloudservice.com" : "bucket.KR.object.ncloudstorage.com";
        ObjectStorageAccessGrantService grants = mock(ObjectStorageAccessGrantService.class);
        CbtumblebugRestApi tumblebug = mock(CbtumblebugRestApi.class);
        ObjectStorageGatewayService service = new ObjectStorageGatewayService(grants, tumblebug);
        ReflectionTestUtils.setField(service, "presignedUrlTtlSeconds", 600);
        ObjectStorageAccessGrant grant = ObjectStorageAccessGrant.builder()
                .namespace("default").objectStorageId("bucket-id").storageAlias("selected-data")
                .credentialHolder("admin").providerName(provider).build();
        when(grants.authorizeStorage("grant-token", "selected-data")).thenReturn(grant);
        when(grants.authorizeObjectKey(grant, "sample.csv", false)).thenReturn("sample.csv");
        when(tumblebug.generateObjectStoragePresignedUrl(
                "default", "bucket-id", "sample.csv", "download", 600, "admin"))
                .thenReturn(response(url, Map.of()));

        ObjectStoragePresignedUrlResponse adjusted = service.createPresignedUrl(
                "grant-token",
                ObjectStoragePresignedUrlRequest.builder()
                        .storage("selected-data").objectKey("sample.csv").operation("download").build());

        assertThat(adjusted.getRequiredHeaders()).containsEntry("Host", expectedHost);
    }

    @Test
    void leavesOtherProvidersAndUnrelatedHostsUntouched() {
        ObjectStoragePresignedUrlResponse response = response(NCP_URL, Map.of());
        assertThat(ObjectStorageGatewayService.preserveSignedHost("aws", response)).isSameAs(response);
        assertThat(ObjectStorageGatewayService.preserveSignedHost("alibaba", response)).isSameAs(response);

        ObjectStoragePresignedUrlResponse unrelated = response(
                "https://bucket.KR.object.ncloudstorage.com.evil.example/a?X-Amz-SignedHeaders=host", Map.of());
        assertThat(ObjectStorageGatewayService.preserveSignedHost("ncp", unrelated)).isSameAs(unrelated);
    }

    @Test
    void preservesProviderHostHeaderAndRequiresHostToBeSigned() {
        ObjectStoragePresignedUrlResponse withHost = response(NCP_URL, Map.of("hOsT", "signed.example"));
        assertThat(ObjectStorageGatewayService.preserveSignedHost("ncp", withHost)).isSameAs(withHost);

        ObjectStoragePresignedUrlResponse unsignedHost = response(
                "https://bucket.KR.object.ncpstorage.com/a?X-Amz-SignedHeaders=x-amz-date", Map.of());
        assertThat(ObjectStorageGatewayService.preserveSignedHost("ncp", unsignedHost)).isSameAs(unsignedHost);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"aws", "alibaba", "azure", "gcp", "ibm", "tencent"})
    void doesNotApplyCompatibilityWorkaroundToOtherStorageProviders(String provider) {
        ObjectStoragePresignedUrlResponse original = response(NCP_URL, Map.of());
        assertThat(ObjectStorageGatewayService.preserveSignedHost(provider, original)).isSameAs(original);
    }

    @Test
    void handlesNullHeadersAndPreservesJapaneseEndpointCaseAndPort() {
        ObjectStoragePresignedUrlResponse original = response(
                "https://JP.object.ncpstorage.com:8443/sample.csv?X-Amz-SignedHeaders=host", null);
        original.setMethod("PUT");
        ObjectStoragePresignedUrlResponse adjusted = ObjectStorageGatewayService.preserveSignedHost("ncp", original);
        assertThat(adjusted.getRequiredHeaders()).containsEntry("Host", "JP.object.ncpstorage.com:8443");
        assertThat(adjusted.getMethod()).isEqualTo("PUT");
        assertThat(adjusted.getPresignedUrl()).isEqualTo(original.getPresignedUrl());
    }

    @Test
    void malformedUrlErrorsDoNotIncludeTheSignedQuery() {
        ObjectStoragePresignedUrlResponse original = response(
                "https://KR.object.ncloudstorage.com/a bad path?secret-query", Map.of());
        assertThatThrownBy(() -> ObjectStorageGatewayService.preserveSignedHost("ncp", original))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret-query");
    }

    private ObjectStoragePresignedUrlResponse response(String url, Map<String, String> headers) {
        return ObjectStoragePresignedUrlResponse.builder()
                .presignedUrl(url).method("GET").expires(600L).requiredHeaders(headers).build();
    }
}
