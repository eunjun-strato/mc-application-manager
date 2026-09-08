package kr.co.mcmp.softwarecatalog.application.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.mcmp.softwarecatalog.application.model.K8sObjectStorageTunnel;

public interface K8sObjectStorageTunnelRepository extends JpaRepository<K8sObjectStorageTunnel, Long> {
    List<K8sObjectStorageTunnel> findByDesiredStateNot(String state);
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update K8sObjectStorageTunnel t set t.leaseOwner=:owner, t.leaseUntil=:until where t.deploymentId=:id and (t.leaseOwner=:owner or t.leaseUntil is null or t.leaseUntil<:now)")
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update K8sObjectStorageTunnel t set t.leaseUntil=:until where t.leaseOwner=:owner and t.leaseUntil>=:now")
    int renew(@Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update K8sObjectStorageTunnel t set t.leaseOwner=null, t.leaseUntil=null where t.leaseOwner=:owner")
    void release(@Param("owner") String owner);
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update K8sObjectStorageTunnel t set t.desiredState=:state, t.updatedAt=:now where t.deploymentId=:id")
    void desire(@Param("id") Long id, @Param("state") String state, @Param("now") Instant now);
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update K8sObjectStorageTunnel t set t.status=:status, t.updatedAt=:now where t.deploymentId=:id and t.leaseOwner=:owner")
    void status(@Param("id") Long id, @Param("owner") String owner, @Param("status") String status, @Param("now") Instant now);
}

