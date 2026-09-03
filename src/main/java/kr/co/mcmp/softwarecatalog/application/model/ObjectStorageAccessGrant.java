package kr.co.mcmp.softwarecatalog.application.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import kr.co.mcmp.softwarecatalog.application.constants.ObjectStorageAccessMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "OBJECT_STORAGE_ACCESS_GRANT",
        indexes = {
                @Index(name = "idx_os_grant_token_hash", columnList = "token_hash"),
                @Index(name = "idx_os_grant_deployment_vm", columnList = "deployment_id,vm_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageAccessGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "vm_id", nullable = false)
    private String vmId;

    @Column(name = "namespace_id", nullable = false)
    private String namespace;

    @Column(name = "object_storage_id", nullable = false)
    private String objectStorageId;

    @Column(name = "storage_alias", nullable = false, length = 64)
    private String storageAlias;

    @Column(name = "object_prefix")
    private String prefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false, length = 16)
    private ObjectStorageAccessMode accessMode;

    @Column(name = "credential_holder")
    private String credentialHolder;

    @Column(name = "provider_name")
    private String providerName;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
