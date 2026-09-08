package kr.co.mcmp.softwarecatalog.application.model;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Durable lifecycle and ownership only; credentials remain in a Kubernetes Secret. */
@Entity
@Table(name = "k8s_object_storage_tunnel")
@Getter @Setter
public class K8sObjectStorageTunnel {
    @Id private Long deploymentId;
    @Column(nullable = false) private String namespace;
    @Column(nullable = false) private String clusterName;
    @Column(nullable = false) private String releaseName;
    @Column(nullable = false) private String workloadUid;
    @Column(nullable = false) private String secretUid;
    private String desiredState = "ACTIVE";
    private String status = "STARTING";
    private String leaseOwner;
    private Instant leaseUntil;
    private Instant updatedAt;
}
