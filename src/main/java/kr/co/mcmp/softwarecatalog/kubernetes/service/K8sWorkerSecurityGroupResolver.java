package kr.co.mcmp.softwarecatalog.kubernetes.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.K8sClusterDto;
import lombok.RequiredArgsConstructor;

/** Resolves actual attachments, never treats cluster network SGs as worker attachments. */
@Service
@RequiredArgsConstructor
public class K8sWorkerSecurityGroupResolver {
    private final CbtumblebugRestApi tumblebug;

    public synchronized String resolve(String namespace, K8sClusterDto cluster) {
        if (cluster == null || cluster.getNetwork() == null)
            throw invalid("Missing cluster network metadata");
        String connection = required(cluster.getConnectionName(), "connection name");
        String vnet = required(cluster.getNetwork().getVNetId(), "registered VNet");
        Set<String> workers = new LinkedHashSet<>();
        for (var group : cluster.getEffectiveNodeGroups()) {
            if (group.getEffectiveNodes().isEmpty())
                throw invalid("A node group has no discoverable workers; retry after nodes are ready");
            for (var node : group.getEffectiveNodes()) workers.add(required(node.getSystemId(), "worker CSP ID"));
        }
        if (workers.isEmpty()) throw invalid("No workers are available for attachment verification");

        Set<String> common = null;
        String workerVpc = null;
        for (String worker : workers) {
            JsonNode info = tumblebug.getCspWorkerInfo(connection, worker);
            if (!worker.equals(info.path("IId").path("SystemId").asText()))
                throw invalid("Worker identity does not match the cluster node list");
            String vpc = required(info.path("VpcIID").path("SystemId").asText(), "worker VPC");
            if (workerVpc != null && !workerVpc.equals(vpc)) throw invalid("Workers span different VPCs");
            workerVpc = vpc;
            Set<String> attached = new LinkedHashSet<>();
            for (JsonNode sg : info.path("SecurityGroupIIds")) {
                String id = sg.path("SystemId").asText();
                if (!id.isBlank()) attached.add(id);
            }
            if (attached.isEmpty()) throw invalid("Worker security group attachments are unavailable");
            if (common == null) common = attached; else common.retainAll(attached);
        }
        if (common == null || common.isEmpty()) throw invalid("No security group is shared by all workers");
        JsonNode vnetInfo = tumblebug.getVNetSecurityMetadata(namespace, vnet);
        if (!workerVpc.equals(vnetInfo.path("cspResourceId").asText())
                || !connection.equals(vnetInfo.path("connectionName").asText()))
            throw invalid("Registered VNet does not match the workers' VPC and connection");

        JsonNode registered = tumblebug.listSecurityGroupMetadata(namespace).path("securityGroup");
        if (!registered.isArray()) throw invalid("Could not list registered security groups");
        Set<String> declared = new LinkedHashSet<>();
        List<String> declaredIds = cluster.getNetwork().getSecurityGroupIds();
        for (JsonNode sg : registered) {
            if (connection.equals(sg.path("connectionName").asText()) && vnet.equals(sg.path("vNetId").asText())
                    && declaredIds != null && declaredIds.contains(sg.path("id").asText())
                    && common.contains(sg.path("cspResourceId").asText()))
                declared.add(sg.path("cspResourceId").asText());
        }
        String selected = declared.size() == 1 ? declared.iterator().next() : null;
        // EKS exposes its auto-created cluster SG separately; use it only after
        // verifying attachment on EVERY worker (custom launch templates may omit it).
        if (selected == null && connectionProviderIsAws(cluster)) {
            Set<String> candidates = new LinkedHashSet<>();
            var metadata = cluster.getNetwork().getKeyValueList();
            if (metadata != null) for (var item : metadata)
                if ("ClusterSecurityGroupId".equals(item.getKey()) && common.contains(item.getValue()))
                    candidates.add(item.getValue());
            if (candidates.size() == 1) selected = candidates.iterator().next();
        }
        if (selected == null && common.size() == 1) selected = common.iterator().next();
        if (selected == null) throw invalid("Several worker SGs match; an unambiguous group is required");

        String existing = registeredId(registered, connection, vnet, selected);
        if (existing != null) return existing;
        // Deterministic name makes registration reusable across AM instances and retries.
        String name = "am-worker-" + UUID.nameUUIDFromBytes((connection + ":" + selected)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        JsonNode created;
        try {
            created = tumblebug.registerExistingSecurityGroup(namespace, connection, vnet, name, selected);
        } catch (RuntimeException failure) {
            String raced = registeredId(tumblebug.listSecurityGroupMetadata(namespace).path("securityGroup"),
                    connection, vnet, selected);
            if (raced != null) return raced;
            throw failure;
        }
        if (!selected.equals(created.path("cspResourceId").asText())
                || !connection.equals(created.path("connectionName").asText())
                || !vnet.equals(created.path("vNetId").asText()))
            throw invalid("Registered security group identity did not match the verified target");
        return required(created.path("id").asText(), "registered security group ID");
    }

    private static boolean connectionProviderIsAws(K8sClusterDto cluster) {
        return cluster.getConnectionConfig() != null && "aws".equalsIgnoreCase(cluster.getConnectionConfig().getProviderName());
    }

    private static String registeredId(JsonNode list, String connection, String vnet, String cspId) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode sg : list) if (connection.equals(sg.path("connectionName").asText())
                && vnet.equals(sg.path("vNetId").asText()) && cspId.equals(sg.path("cspResourceId").asText()))
            ids.add(required(sg.path("id").asText(), "registered security group ID"));
        if (ids.size() > 1) throw invalid("Multiple registrations refer to the same worker SG");
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw invalid("Missing " + field);
        return value;
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Cannot verify worker Security Group: " + message);
    }
}
