package kr.co.mcmp.softwarecatalog.application.service;

import java.util.List;

import org.springframework.stereotype.Service;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.VmAccessInfo;
import kr.co.mcmp.softwarecatalog.application.dto.DeploymentRequest;
import kr.co.mcmp.softwarecatalog.application.exception.ApplicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens a VM application port through CB-Tumblebug's additive firewall-rule
 * endpoint. This service deliberately never performs a full Security Group
 * update and never chooses arbitrarily when multiple groups are attached.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VmSecurityGroupExposureService {

    private final CbtumblebugRestApi cbtumblebugRestApi;

    public void addRestrictedInboundRule(DeploymentRequest request, VmAccessInfo vmAccessInfo) {
        if (!Boolean.TRUE.equals(request.getOpenServicePort())) {
            return;
        }

        int port = validatePort(request.getServicePort());
        String cidr = validateRestrictedIpv4Cidr(request.getServicePortCidr());
        String securityGroupId = resolveSingleAttachedSecurityGroup(vmAccessInfo);

        cbtumblebugRestApi.addInboundTcpFirewallRule(
                request.getNamespace(),
                securityGroupId,
                port,
                cidr);
        log.info(
                "Added restricted inbound TCP rule through Tumblebug: vmId={}, securityGroupId={}, port={}, cidr={}",
                vmAccessInfo.getId(),
                securityGroupId,
                port,
                cidr);
    }

    private int validatePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new ApplicationException("A service port between 1 and 65535 is required for direct access");
        }
        return port;
    }

    private String validateRestrictedIpv4Cidr(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            throw new ApplicationException("A restricted IPv4 CIDR is required for direct access");
        }

        String normalized = cidr.trim();
        String[] cidrParts = normalized.split("/", -1);
        if (cidrParts.length != 2) {
            throw new ApplicationException("Service port CIDR must use IPv4 CIDR notation, for example 203.0.113.10/32");
        }

        String[] octets = cidrParts[0].split("\\.", -1);
        if (octets.length != 4) {
            throw new ApplicationException("Service port CIDR must be a valid IPv4 CIDR");
        }
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255 || !String.valueOf(value).equals(octet)) {
                    throw new NumberFormatException("invalid IPv4 octet");
                }
            } catch (NumberFormatException e) {
                throw new ApplicationException("Service port CIDR must be a valid IPv4 CIDR");
            }
        }

        try {
            int prefixLength = Integer.parseInt(cidrParts[1]);
            if (prefixLength < 1 || prefixLength > 32) {
                throw new ApplicationException("Public any CIDR is not allowed; use a prefix between /1 and /32");
            }
        } catch (NumberFormatException e) {
            throw new ApplicationException("Service port CIDR must contain a valid prefix length");
        }
        return normalized;
    }

    private String resolveSingleAttachedSecurityGroup(VmAccessInfo vmAccessInfo) {
        if (vmAccessInfo == null) {
            throw new ApplicationException("VM information is required to configure direct access");
        }

        List<String> securityGroupIds = vmAccessInfo.getSecurityGroupIds() == null
                ? List.of()
                : vmAccessInfo.getSecurityGroupIds().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();

        if (securityGroupIds.isEmpty()) {
            throw new ApplicationException("The target VM has no attached Security Group");
        }
        if (securityGroupIds.size() != 1) {
            throw new ApplicationException(
                    "The target VM has multiple Security Groups; Application Manager will not choose one automatically");
        }
        return securityGroupIds.get(0);
    }
}
