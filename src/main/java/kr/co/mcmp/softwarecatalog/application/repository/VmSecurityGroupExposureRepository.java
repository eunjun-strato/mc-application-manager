package kr.co.mcmp.softwarecatalog.application.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import kr.co.mcmp.softwarecatalog.application.model.VmSecurityGroupExposure;

@Repository
public interface VmSecurityGroupExposureRepository extends JpaRepository<VmSecurityGroupExposure, Long> {

    Optional<VmSecurityGroupExposure> findByDeploymentId(Long deploymentId);

    Optional<VmSecurityGroupExposure> findByDeploymentIdAndReleasedAtIsNull(Long deploymentId);

    List<VmSecurityGroupExposure>
            findByNamespaceAndSecurityGroupIdAndServicePortAndAllowedCidrAndReleasedAtIsNull(
                    String namespace,
                    String securityGroupId,
                    Integer servicePort,
                    String allowedCidr);
}
