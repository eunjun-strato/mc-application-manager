package kr.co.mcmp.softwarecatalog.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageTunnel;

public interface ObjectStorageTunnelRepository extends JpaRepository<ObjectStorageTunnel, String> {
    Optional<ObjectStorageTunnel> findByDeploymentId(Long deploymentId);
    Optional<ObjectStorageTunnel> findByNamespaceAndMciIdAndVmIdAndContainerId(String namespace, String mciId, String vmId, String containerId);
    List<ObjectStorageTunnel> findByDesiredStateNot(String state);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update ObjectStorageTunnel t set t.leaseOwner=:owner, t.leaseUntil=:until where t.id=:id and (t.leaseOwner=:owner or t.leaseUntil is null or t.leaseUntil<:now)")
    int claim(@Param("id") String id, @Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update ObjectStorageTunnel t set t.leaseUntil=:until where t.leaseOwner=:owner and t.leaseUntil>=:now")
    int renew(@Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update ObjectStorageTunnel t set t.leaseOwner=null, t.leaseUntil=null where t.leaseOwner=:owner")
    void release(@Param("owner") String owner);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update ObjectStorageTunnel t set t.desiredState=:state, t.updatedAt=:now where t.id=:id")
    void desire(@Param("id") String id, @Param("state") String state, @Param("now") Instant now);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update ObjectStorageTunnel t set t.status=:status, t.updatedAt=:now where t.id=:id and t.leaseOwner=:owner")
    void status(@Param("id") String id, @Param("owner") String owner, @Param("status") String status, @Param("now") Instant now);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update ObjectStorageTunnel t set t.vmUid=:uid, t.hostKey=:key where t.id=:id and t.leaseOwner=:owner")
    void identity(@Param("id") String id, @Param("owner") String owner, @Param("uid") String uid, @Param("key") String key);
}
