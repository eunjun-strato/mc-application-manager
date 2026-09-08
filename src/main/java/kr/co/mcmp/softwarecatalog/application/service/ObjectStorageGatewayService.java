package kr.co.mcmp.softwarecatalog.application.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStorageListObjectsResponse;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStorageObject;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStoragePresignedUrlResponse;
import kr.co.mcmp.softwarecatalog.application.dto.GrantedObjectStorageDTO;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStoragePresignedUrlRequest;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageAccessGrant;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ObjectStorageGatewayService {
    private final ObjectStorageAccessGrantService grantService;
    private final CbtumblebugRestApi cbtumblebugRestApi;

    @Value("${app.object-storage.presigned-url-ttl-seconds:600}")
    private int presignedUrlTtlSeconds;

    public List<GrantedObjectStorageDTO> listStorages(String token) {
        return grantService.authorize(token).stream()
                .map(grant -> GrantedObjectStorageDTO.builder()
                        .alias(grant.getStorageAlias())
                        .objectStorageId(grant.getObjectStorageId())
                        .provider(grant.getProviderName())
                        .prefix(grant.getPrefix())
                        .accessMode(grant.getAccessMode().name())
                        .expiresAt(grant.getExpiresAt())
                        .build())
                .toList();
    }

    public ObjectStorageListObjectsResponse listObjects(String token, String storageAlias, String requestedPrefix) {
        ObjectStorageAccessGrant grant = grantService.authorizeStorage(token, storageAlias);
        String effectivePrefix = grantService.effectiveListPrefix(grant, requestedPrefix);
        ObjectStorageListObjectsResponse result = cbtumblebugRestApi.listObjectStorageObjects(
                grant.getNamespace(),
                grant.getObjectStorageId(),
                grant.getCredentialHolder());
        List<ObjectStorageObject> objects = result.getObjects() == null
                ? List.of()
                : result.getObjects().stream()
                        .filter(object -> object.getKey() != null && object.getKey().startsWith(effectivePrefix))
                        .toList();
        return ObjectStorageListObjectsResponse.builder().objects(objects).build();
    }

    public ObjectStoragePresignedUrlResponse createPresignedUrl(
            String token,
            ObjectStoragePresignedUrlRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Presigned URL request is required.");
        }
        String operation = request.getOperation() == null
                ? ""
                : request.getOperation().trim().toLowerCase(Locale.ROOT);
        if (!"download".equals(operation) && !"upload".equals(operation)) {
            throw new IllegalArgumentException("Operation must be download or upload.");
        }

        ObjectStorageAccessGrant grant = grantService.authorizeStorage(token, request.getStorage());
        String key = grantService.authorizeObjectKey(grant, request.getObjectKey(), "upload".equals(operation));
        int expiresSeconds = Math.max(60, Math.min(3600, presignedUrlTtlSeconds));
        ObjectStoragePresignedUrlResponse response = cbtumblebugRestApi.generateObjectStoragePresignedUrl(
                grant.getNamespace(),
                grant.getObjectStorageId(),
                key,
                operation,
                expiresSeconds,
                grant.getCredentialHolder());
        return preserveSignedHost(grant.getProviderName(), response);
    }

    static ObjectStoragePresignedUrlResponse preserveSignedHost(
            String provider, ObjectStoragePresignedUrlResponse response) {
        String providerName = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!("ncp".equals(providerName) || "nhn".equals(providerName))
                || response == null || response.getPresignedUrl() == null) {
            return response;
        }
        Map<String, String> existing = response.getRequiredHeaders();
        if (existing != null && existing.keySet().stream().anyMatch("host"::equalsIgnoreCase)) {
            return response;
        }
        URI uri;
        try {
            uri = URI.create(response.getPresignedUrl());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Object Storage returned an invalid presigned URL");
        }
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException("Object Storage returned an invalid presigned URL authority");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean supportedEndpoint = switch (providerName) {
            case "ncp" -> lowerHost.endsWith(".object.ncloudstorage.com")
                    || lowerHost.endsWith(".object.ncpstorage.com");
            case "nhn" -> lowerHost.matches("[a-z0-9]+-api-object-storage\\.nhncloudservice\\.com");
            default -> false;
        };
        if (!supportedEndpoint || uri.getRawQuery() == null) {
            return response;
        }
        boolean signsHost = Arrays.stream(uri.getRawQuery().split("&"))
                .filter(part -> part.startsWith("X-Amz-SignedHeaders="))
                .map(part -> URLDecoder.decode(part.substring(part.indexOf('=') + 1), StandardCharsets.UTF_8))
                .flatMap(value -> Arrays.stream(value.split(";")))
                .anyMatch("host"::equalsIgnoreCase);
        if (!signsHost) {
            return response;
        }
        // requests lowercases URL hosts. NCP/NHN signatures can contain uppercase
        // region codes, so preserve the signed authority as an explicit header.
        // Never rewrite the signed URL or discard provider-supplied headers.
        Map<String, String> headers = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        headers.put("Host", uri.getRawAuthority());
        return ObjectStoragePresignedUrlResponse.builder()
                .method(response.getMethod()).expires(response.getExpires())
                .presignedUrl(response.getPresignedUrl()).requiredHeaders(headers).build();
    }
}
