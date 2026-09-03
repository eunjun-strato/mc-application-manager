package kr.co.mcmp.softwarecatalog.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStorageInfo;
import kr.co.mcmp.softwarecatalog.application.constants.ObjectStorageAccessMode;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageConfiguration;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStorageSelection;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageAccessGrant;
import kr.co.mcmp.softwarecatalog.application.repository.ObjectStorageAccessGrantRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ObjectStorageAccessGrantService {
    private static final Pattern ALIAS_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ObjectStorageAccessGrantRepository repository;
    private final CbtumblebugRestApi cbtumblebugRestApi;

    @Value("${app.object-storage.grant-ttl-hours:8760}")
    private long grantTtlHours;

    public List<ResolvedSelection> resolveSelections(
            String namespace,
            ObjectStorageConfiguration configuration) {
        if (configuration == null || !Boolean.TRUE.equals(configuration.getEnabled())) {
            throw new IllegalArgumentException("Object Storage is not enabled.");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Namespace is required for Object Storage access.");
        }
        if (configuration.getStorages() == null || configuration.getStorages().isEmpty()) {
            throw new IllegalArgumentException("Select at least one Tumblebug Object Storage resource.");
        }

        List<ResolvedSelection> resolved = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        for (ObjectStorageSelection selection : configuration.getStorages()) {
            if (selection == null || selection.getObjectStorageId() == null
                    || selection.getObjectStorageId().isBlank()) {
                throw new IllegalArgumentException("Object Storage ID is required.");
            }

            String objectStorageId = selection.getObjectStorageId().trim();
            String alias = normalizeAlias(selection.getAlias(), objectStorageId);
            if (!aliases.add(alias.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Object Storage aliases must be unique.");
            }

            ObjectStorageInfo info = cbtumblebugRestApi.getObjectStorage(namespace, objectStorageId);
            if (info == null) {
                throw new IllegalArgumentException("Object Storage not found: " + objectStorageId);
            }
            if (!"available".equalsIgnoreCase(info.getStatus())) {
                throw new IllegalArgumentException(
                        "Object Storage is not available: " + objectStorageId + " (" + info.getStatus() + ")");
            }

            ObjectStorageInfo.ConnectionConfig connection = info.getConnectionConfig();
            String provider = connection == null ? "" : safe(connection.getProviderName());
            String credentialHolder = connection == null ? "" : safe(connection.getCredentialHolder());
            ObjectStorageAccessMode accessMode = parseAccessMode(selection.getAccessMode());
            resolved.add(new ResolvedSelection(
                    objectStorageId,
                    alias,
                    normalizePrefix(selection.getPrefix()),
                    accessMode,
                    provider,
                    credentialHolder));
        }
        return resolved;
    }

    @Transactional
    public IssuedAccess issue(
            Long deploymentId,
            String vmId,
            String namespace,
            ObjectStorageConfiguration configuration) {
        if (deploymentId == null || vmId == null || vmId.isBlank()) {
            throw new IllegalArgumentException("Deployment and VM are required for Object Storage access.");
        }
        List<ResolvedSelection> selections = resolveSelections(namespace, configuration);
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String token = "mcmp_os_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String tokenHash = hashToken(token);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(Math.max(1, grantTtlHours));

        List<ObjectStorageAccessGrant> grants = selections.stream()
                .map(selection -> ObjectStorageAccessGrant.builder()
                        .tokenHash(tokenHash)
                        .deploymentId(deploymentId)
                        .vmId(vmId)
                        .namespace(namespace)
                        .objectStorageId(selection.objectStorageId())
                        .storageAlias(selection.alias())
                        .prefix(selection.prefix())
                        .accessMode(selection.accessMode())
                        .credentialHolder(selection.credentialHolder())
                        .providerName(selection.provider())
                        .createdAt(now)
                        .expiresAt(expiresAt)
                        .build())
                .toList();
        repository.saveAll(grants);
        return new IssuedAccess(token, selections, expiresAt);
    }

    public List<ObjectStorageAccessGrant> authorize(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Object Storage access token is required.");
        }
        List<ObjectStorageAccessGrant> grants = repository
                .findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hashToken(token), LocalDateTime.now());
        if (grants.isEmpty()) {
            throw new IllegalArgumentException("Object Storage access token is invalid or expired.");
        }
        return grants;
    }

    public ObjectStorageAccessGrant authorizeStorage(String token, String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Storage alias is required.");
        }
        return authorize(token).stream()
                .filter(grant -> alias.equalsIgnoreCase(grant.getStorageAlias()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Storage is not included in this access grant."));
    }

    public String effectiveListPrefix(ObjectStorageAccessGrant grant, String requestedPrefix) {
        String allowedPrefix = safe(grant.getPrefix());
        String requested = safe(requestedPrefix);
        if (requested.isBlank()) {
            return allowedPrefix;
        }
        validateObjectPath(requested, "Object prefix");
        if (!allowedPrefix.isBlank() && !requested.startsWith(allowedPrefix)) {
            throw new IllegalArgumentException("Requested prefix is outside the granted Object Storage prefix.");
        }
        return requested;
    }

    public String authorizeObjectKey(ObjectStorageAccessGrant grant, String objectKey, boolean upload) {
        if (upload && !grant.getAccessMode().allowsUpload()) {
            throw new IllegalArgumentException("This Object Storage grant is read-only.");
        }
        String key = safe(objectKey);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Object key is required.");
        }
        validateObjectPath(key, "Object key");
        if (grant.getPrefix() != null && !grant.getPrefix().isBlank()
                && !key.startsWith(grant.getPrefix())) {
            throw new IllegalArgumentException("Object key is outside the granted Object Storage prefix.");
        }
        return key;
    }

    @Transactional
    public void revoke(Long deploymentId, String vmId) {
        if (deploymentId != null && vmId != null && !vmId.isBlank()) {
            repository.revokeByDeploymentIdAndVmId(deploymentId, vmId, LocalDateTime.now());
        }
    }

    private ObjectStorageAccessMode parseAccessMode(String value) {
        if (value == null || value.isBlank()) {
            return ObjectStorageAccessMode.READ_ONLY;
        }
        try {
            return ObjectStorageAccessMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Access mode must be READ_ONLY or READ_WRITE.");
        }
    }

    private String normalizeAlias(String requestedAlias, String objectStorageId) {
        String alias = safe(requestedAlias);
        if (alias.isBlank()) {
            alias = objectStorageId;
        }
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new IllegalArgumentException(
                    "Object Storage alias must contain only letters, numbers, dot, underscore, or hyphen.");
        }
        return alias;
    }

    private String normalizePrefix(String value) {
        String prefix = safe(value);
        if (prefix.isBlank()) {
            return "";
        }
        validateObjectPath(prefix, "Object prefix");
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private void validateObjectPath(String value, String label) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException(label + " exceeds the 1024-byte Object Storage key limit.");
        }
        if (value.startsWith("/") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " contains an invalid path.");
        }
        for (String segment : value.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(label + " cannot contain '..' path segments.");
            }
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ResolvedSelection(
            String objectStorageId,
            String alias,
            String prefix,
            ObjectStorageAccessMode accessMode,
            String provider,
            String credentialHolder) {
    }

    public record IssuedAccess(
            String token,
            List<ResolvedSelection> selections,
            LocalDateTime expiresAt) {
    }
}
