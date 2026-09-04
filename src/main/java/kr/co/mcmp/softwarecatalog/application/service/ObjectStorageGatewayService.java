package kr.co.mcmp.softwarecatalog.application.service;

import java.util.List;
import java.util.Locale;

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
        return cbtumblebugRestApi.generateObjectStoragePresignedUrl(
                grant.getNamespace(),
                grant.getObjectStorageId(),
                key,
                operation,
                expiresSeconds,
                grant.getCredentialHolder());
    }
}
