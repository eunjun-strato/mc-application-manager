package kr.co.mcmp.softwarecatalog.application.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "VM_SECURITY_GROUP_EXPOSURE",
        indexes = {
                @Index(name = "idx_vm_sg_exposure_rule", columnList = "namespace_id,security_group_id,service_port,allowed_cidr"),
                @Index(name = "idx_vm_sg_exposure_active", columnList = "released_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_vm_sg_exposure_deployment", columnNames = "deployment_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VmSecurityGroupExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "vm_id", nullable = false)
    private String vmId;

    @Column(name = "namespace_id", nullable = false)
    private String namespace;

    @Column(name = "security_group_id", nullable = false)
    private String securityGroupId;

    @Column(name = "service_port", nullable = false)
    private Integer servicePort;

    @Column(name = "allowed_cidr", nullable = false)
    private String allowedCidr;

    /** True only when the rule was originally added by Application Manager. */
    @Column(name = "managed_by_application_manager", nullable = false)
    private Boolean managedByApplicationManager;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "provisioned_at")
    private LocalDateTime provisionedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "rule_removed_at")
    private LocalDateTime ruleRemovedAt;
}
