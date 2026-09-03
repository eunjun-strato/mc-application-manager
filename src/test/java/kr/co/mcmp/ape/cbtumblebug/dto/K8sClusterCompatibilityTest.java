package kr.co.mcmp.ape.cbtumblebug.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class K8sClusterCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesLegacyK8sClusterInfoAndSpiderNodeGroups() throws Exception {
        String json = """
                {
                  "K8sClusterInfo": [
                    {
                      "id": "legacy-cluster",
                      "connectionName": "aws-ap-northeast-2",
                      "spiderViewK8sClusterDetail": {
                        "NodeGroupList": [
                          {
                            "IId": {"NameId": "legacy-ng", "SystemId": "ng-system-id"},
                            "VMSpecName": "t3.medium",
                            "OnAutoScaling": true,
                            "DesiredNodeSize": 2,
                            "Nodes": [
                              {"NameId": "legacy-node-1", "SystemId": "node-system-id-1"}
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        K8sClusterResponse response = objectMapper.readValue(json, K8sClusterResponse.class);

        assertThat(response.getK8sClusterInfo()).hasSize(1);
        K8sClusterDto cluster = response.getK8sClusterInfo().get(0);
        assertThat(cluster.getId()).isEqualTo("legacy-cluster");
        assertThat(cluster.getEffectiveNodeGroups()).hasSize(1);

        K8sClusterDto.NodeGroup nodeGroup = cluster.getEffectiveNodeGroups().get(0);
        assertThat(nodeGroup.getEffectiveName()).isEqualTo("legacy-ng");
        assertThat(nodeGroup.getEffectiveVmSpecName()).isEqualTo("t3.medium");
        assertThat(nodeGroup.isOnAutoScaling()).isTrue();
        assertThat(nodeGroup.getDesiredNodeSize()).isEqualTo(2);
        assertThat(nodeGroup.getEffectiveNodes())
                .extracting(K8sClusterDto.IID::getNameId)
                .containsExactly("legacy-node-1");
    }

    @Test
    void deserializesGeneralizedClusterInfoAndTopLevelNodeGroups() throws Exception {
        String json = """
                {
                  "ClusterInfo": [
                    {
                      "resourceType": "k8s",
                      "id": "generalized-cluster",
                      "connectionName": "aws-ap-northeast-2",
                      "nodeGroupCount": 1,
                      "nodeGroups": [
                        {
                          "resourceType": "k8sNodeGroup",
                          "name": "generalized-ng",
                          "specId": "aws+ap-northeast-2+t3.medium",
                          "imageId": "default",
                          "onAutoScaling": true,
                          "desiredNodeSize": 3,
                          "minNodeSize": 1,
                          "maxNodeSize": 5,
                          "connectionName": "aws-ap-northeast-2",
                          "spiderViewK8sNodeGroupDetail": {
                            "IId": {"NameId": "generalized-ng", "SystemId": "ng-system-id"},
                            "VMSpecName": "t3.medium",
                            "Nodes": [
                              {"NameId": "generalized-node-1", "SystemId": "node-system-id-1"},
                              {"NameId": "generalized-node-2", "SystemId": "node-system-id-2"}
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        K8sClusterResponse response = objectMapper.readValue(json, K8sClusterResponse.class);

        assertThat(response.getK8sClusterInfo()).hasSize(1);
        K8sClusterDto cluster = response.getK8sClusterInfo().get(0);
        assertThat(cluster.getId()).isEqualTo("generalized-cluster");
        assertThat(cluster.getEffectiveNodeGroups()).hasSize(1);

        K8sClusterDto.NodeGroup nodeGroup = cluster.getEffectiveNodeGroups().get(0);
        assertThat(nodeGroup.getEffectiveName()).isEqualTo("generalized-ng");
        assertThat(nodeGroup.getEffectiveVmSpecName()).isEqualTo("t3.medium");
        assertThat(nodeGroup.isOnAutoScaling()).isTrue();
        assertThat(nodeGroup.getDesiredNodeSize()).isEqualTo(3);
        assertThat(nodeGroup.getMinNodeSize()).isEqualTo(1);
        assertThat(nodeGroup.getMaxNodeSize()).isEqualTo(5);
        assertThat(nodeGroup.getEffectiveNodes())
                .extracting(K8sClusterDto.IID::getNameId)
                .containsExactly("generalized-node-1", "generalized-node-2");
    }

    @Test
    void fallsBackToGeneralizedFieldsWhenSpiderDetailIsAbsent() throws Exception {
        String json = """
                {
                  "name": "generalized-cluster",
                  "nodeGroups": [
                    {
                      "name": "generalized-ng",
                      "specId": "aws+ap-northeast-2+t3.medium",
                      "k8sNodes": [
                        {"cspResourceName": "node-a", "cspResourceId": "i-001"},
                        {"cspResourceId": "i-002"}
                      ]
                    }
                  ]
                }
                """;

        K8sClusterDto cluster = objectMapper.readValue(json, K8sClusterDto.class);
        K8sClusterDto.NodeGroup nodeGroup = cluster.getEffectiveNodeGroups().get(0);

        assertThat(nodeGroup.getEffectiveName()).isEqualTo("generalized-ng");
        assertThat(nodeGroup.getEffectiveVmSpecName()).isEqualTo("aws+ap-northeast-2+t3.medium");
        assertThat(nodeGroup.getEffectiveNodes())
                .extracting(K8sClusterDto.IID::getNameId)
                .containsExactly("node-a", "i-002");
    }

    @Test
    void prefersGeneralizedNodeGroupsWhenBothResponseShapesArePresent() throws Exception {
        K8sClusterDto.NodeGroup generalized = new K8sClusterDto.NodeGroup();
        generalized.setName("generalized-ng");

        K8sClusterDto.NodeGroup legacy = new K8sClusterDto.NodeGroup();
        legacy.setIid(new K8sClusterDto.IID("legacy-ng", null));
        K8sClusterDto.SpiderViewK8sClusterDetail spiderDetail = new K8sClusterDto.SpiderViewK8sClusterDetail();
        spiderDetail.setNodeGroupList(List.of(legacy));

        K8sClusterDto cluster = new K8sClusterDto();
        cluster.setNodeGroups(List.of(generalized));
        cluster.setSpiderViewK8sClusterDetail(spiderDetail);

        assertThat(cluster.getEffectiveNodeGroups())
                .extracting(K8sClusterDto.NodeGroup::getEffectiveName)
                .containsExactly("generalized-ng");
    }

    @Test
    void fallsBackToLegacyCspNodeGroups() {
        K8sClusterDto.NodeGroup legacy = new K8sClusterDto.NodeGroup();
        legacy.setIid(new K8sClusterDto.IID("csp-ng", null));

        K8sClusterDto.CspViewK8sClusterDetail cspDetail = new K8sClusterDto.CspViewK8sClusterDetail();
        cspDetail.setNodeGroupList(List.of(legacy));

        K8sClusterDto cluster = new K8sClusterDto();
        cluster.setCspViewK8sClusterDetail(cspDetail);

        assertThat(cluster.getEffectiveNodeGroups())
                .extracting(K8sClusterDto.NodeGroup::getEffectiveName)
                .containsExactly("csp-ng");
    }

    @Test
    void returnsEmptyNodeGroupsForIncompleteResponse() {
        K8sClusterDto cluster = new K8sClusterDto();

        assertThat(cluster.getEffectiveNodeGroups()).isEmpty();
    }

    @Test
    void doesNotExposeInternalCompatibilityPropertiesWhenSerializing() throws Exception {
        K8sClusterDto.NodeGroup nodeGroup = new K8sClusterDto.NodeGroup();
        nodeGroup.setName("generalized-ng");

        K8sClusterDto cluster = new K8sClusterDto();
        cluster.setNodeGroups(List.of(nodeGroup));

        String json = objectMapper.writeValueAsString(cluster);

        assertThat(json)
                .doesNotContain("effectiveNodeGroups")
                .doesNotContain("effectiveName")
                .doesNotContain("effectiveVmSpecName")
                .doesNotContain("effectiveNodes");
    }
}
