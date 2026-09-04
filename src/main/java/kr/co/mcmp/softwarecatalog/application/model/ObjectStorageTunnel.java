package kr.co.mcmp.softwarecatalog.application.model;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Durable desired state only. SSH private keys and bearer tokens are never stored here. */
@Entity
@Table(name = "object_storage_tunnel", uniqueConstraints = @UniqueConstraint(columnNames = "deployment_id"))
@Getter
@Setter
public class ObjectStorageTunnel {
    @Id
    private String id;
    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;
    @Column(nullable = false)
    private String namespace;
    @Column(nullable = false)
    private String mciId;
    @Column(nullable = false)
    private String vmId;
    @Column(nullable = false, length = 64)
    private String containerId;
    private String vmUid;
    @Column(length = 2048)
    private String hostKey;
    @Column(nullable = false)
    private String desiredState = "ACTIVE";
    private String status = "STARTING";
    private String leaseOwner;
    private Instant leaseUntil;
    private Instant updatedAt;
}
