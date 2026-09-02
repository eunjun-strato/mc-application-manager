package kr.co.mcmp.softwarecatalog.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.VmAccessInfo;
import kr.co.mcmp.softwarecatalog.application.dto.DeploymentRequest;
import kr.co.mcmp.softwarecatalog.application.exception.ApplicationException;

@ExtendWith(MockitoExtension.class)
class VmSecurityGroupExposureServiceTest {

    @Mock
    private CbtumblebugRestApi cbtumblebugRestApi;

    private VmSecurityGroupExposureService service;

    @BeforeEach
    void setUp() {
        service = new VmSecurityGroupExposureService(cbtumblebugRestApi);
    }

    @Test
    void leavesSecurityGroupUntouchedByDefault() {
        DeploymentRequest request = DeploymentRequest.builder().build();

        service.addRestrictedInboundRule(request, null);

        verify(cbtumblebugRestApi, never())
                .addInboundTcpFirewallRule(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addsOnlyTheRequestedTcpRuleToTheSingleAttachedGroup() {
        DeploymentRequest request = directAccessRequest("203.0.113.10/32");
        VmAccessInfo vm = vmWithSecurityGroups("vm01", List.of("sg01"));

        service.addRestrictedInboundRule(request, vm);

        verify(cbtumblebugRestApi)
                .addInboundTcpFirewallRule("ns01", "sg01", 8888, "203.0.113.10/32");
    }

    @Test
    void refusesToChooseArbitrarilyBetweenMultipleGroups() {
        DeploymentRequest request = directAccessRequest("203.0.113.10/32");
        VmAccessInfo vm = vmWithSecurityGroups("vm01", List.of("sg01", "sg02"));

        assertThatThrownBy(() -> service.addRestrictedInboundRule(request, vm))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("multiple Security Groups");
    }

    @Test
    void rejectsPublicAnyCidr() {
        DeploymentRequest request = directAccessRequest("0.0.0.0/0");
        VmAccessInfo vm = vmWithSecurityGroups("vm01", List.of("sg01"));

        assertThatThrownBy(() -> service.addRestrictedInboundRule(request, vm))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Public any CIDR");
    }

    private DeploymentRequest directAccessRequest(String cidr) {
        return DeploymentRequest.builder()
                .namespace("ns01")
                .servicePort(8888)
                .openServicePort(true)
                .servicePortCidr(cidr)
                .build();
    }

    private VmAccessInfo vmWithSecurityGroups(String vmId, List<String> securityGroupIds) {
        VmAccessInfo vm = new VmAccessInfo();
        vm.setId(vmId);
        vm.setSecurityGroupIds(securityGroupIds);
        return vm;
    }
}
