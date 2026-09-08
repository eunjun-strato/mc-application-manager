package kr.co.mcmp.softwarecatalog.kubernetes.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.K8sClusterDto;

class K8sWorkerSecurityGroupResolverTest {
    final ObjectMapper mapper = new ObjectMapper();
    final CbtumblebugRestApi tb = mock(CbtumblebugRestApi.class);
    final K8sWorkerSecurityGroupResolver resolver = new K8sWorkerSecurityGroupResolver(tb);
    JsonNode json(String text) throws Exception { return mapper.readTree(text); }
    K8sClusterDto cluster() throws Exception {
        return mapper.readValue("""
            {"connectionName":"aws-seoul","connectionConfig":{"providerName":"aws"},
             "network":{"vNetId":"vnet","securityGroupIds":["declared"],
               "keyValueList":[{"key":"ClusterSecurityGroupId","value":"sg-cluster"}]},
             "nodeGroups":[{"Nodes":[{"SystemId":"i-a"},{"SystemId":"i-b"}]}]}
            """,K8sClusterDto.class);
    }
    void setup(String... groups) throws Exception {
        for (String id : List.of("i-a","i-b")) {
            var body=mapper.createObjectNode(); body.putObject("IId").put("SystemId",id);
            body.putObject("VpcIID").put("SystemId","vpc-real");
            var list=body.putArray("SecurityGroupIIds");
            for(String group:groups) list.addObject().put("SystemId",group);
            when(tb.getCspWorkerInfo("aws-seoul",id)).thenReturn(body);
        }
        when(tb.getVNetSecurityMetadata("default","vnet"))
                .thenReturn(json("{\"connectionName\":\"aws-seoul\",\"cspResourceId\":\"vpc-real\"}"));
        when(tb.listSecurityGroupMetadata("default")).thenReturn(json("""
            {"securityGroup":[{"id":"declared","connectionName":"aws-seoul","vNetId":"vnet","cspResourceId":"sg-declared"}]}
            """));
        when(tb.registerExistingSecurityGroup(eq("default"),eq("aws-seoul"),eq("vnet"),anyString(),anyString()))
                .thenAnswer(i->mapper.valueToTree(Map.of("id","registered","connectionName","aws-seoul",
                        "vNetId","vnet","cspResourceId",i.getArgument(4,String.class))));
    }
    @Test void eksDoesNotUseDeclaredSgWhenItIsOnlyASshSource() throws Exception {
        setup("sg-remote","sg-cluster");
        assertThat(resolver.resolve("default",cluster())).isEqualTo("registered");
        verify(tb).registerExistingSecurityGroup(eq("default"),eq("aws-seoul"),eq("vnet"),startsWith("am-worker-"),eq("sg-cluster"));
        verify(tb,never()).registerExistingSecurityGroup(any(),any(),any(),any(),eq("sg-declared"));
    }
    @Test void reusesDeclaredRegistrationWhenActuallyAttachedToAllWorkers() throws Exception {
        setup("sg-declared");
        assertThat(resolver.resolve("default",cluster())).isEqualTo("declared");
        verify(tb,never()).registerExistingSecurityGroup(any(),any(),any(),any(),any());
    }
    @Test void existingWorkerRegistrationIsReused() throws Exception {
        setup("sg-cluster");
        when(tb.listSecurityGroupMetadata("default")).thenReturn(json("""
            {"securityGroup":[{"id":"worker","connectionName":"aws-seoul","vNetId":"vnet","cspResourceId":"sg-cluster"}]}
            """));
        assertThat(resolver.resolve("default",cluster())).isEqualTo("worker");
        verify(tb,never()).registerExistingSecurityGroup(any(),any(),any(),any(),any());
    }
    @Test void launchTemplateWorkersWithoutClusterSgFailWhenAmbiguous() throws Exception {
        setup("sg-custom-a","sg-custom-b");
        assertThatThrownBy(()->resolver.resolve("default",cluster())).hasMessageContaining("unambiguous");
        verify(tb,never()).registerExistingSecurityGroup(any(),any(),any(),any(),any());
    }
    @Test void refusesGroupsNotSharedByAllWorkers() throws Exception {
        setup("sg-cluster");
        when(tb.getCspWorkerInfo("aws-seoul","i-b")).thenReturn(json("""
            {"IId":{"SystemId":"i-b"},"VpcIID":{"SystemId":"vpc-real"},"SecurityGroupIIds":[{"SystemId":"sg-other"}]}
            """));
        assertThatThrownBy(()->resolver.resolve("default",cluster())).hasMessageContaining("shared by all");
        verify(tb,never()).registerExistingSecurityGroup(any(),any(),any(),any(),any());
    }
    @Test void refusesWrongWorkerIdentity() throws Exception {
        setup("sg-cluster");
        when(tb.getCspWorkerInfo("aws-seoul","i-b")).thenReturn(json("{\"IId\":{\"SystemId\":\"i-foreign\"}}"));
        assertThatThrownBy(()->resolver.resolve("default",cluster())).hasMessageContaining("identity");
    }
    @Test void refusesVnetMismatch() throws Exception {
        setup("sg-cluster");
        when(tb.getVNetSecurityMetadata("default","vnet")).thenReturn(json("{\"cspResourceId\":\"vpc-foreign\"}"));
        assertThatThrownBy(()->resolver.resolve("default",cluster())).hasMessageContaining("VNet");
    }
    @Test void refusesEmptyWorkerGroups() throws Exception {
        var c=cluster(); c.setNodeGroups(List.of(new K8sClusterDto.NodeGroup()));
        assertThatThrownBy(()->resolver.resolve("default",c)).hasMessageContaining("no discoverable workers");
        verifyNoInteractions(tb);
    }
    @Test void rejectsMismatchedRegistrationResponse() throws Exception {
        setup("sg-cluster");
        when(tb.registerExistingSecurityGroup(any(),any(),any(),any(),any())).thenReturn(json("{\"id\":\"bad\",\"cspResourceId\":\"sg-foreign\"}"));
        assertThatThrownBy(()->resolver.resolve("default",cluster())).hasMessageContaining("identity");
    }
    @Test void recoversRegistrationRaceByRecheckingExactCspIdentity() throws Exception {
        setup("sg-cluster");
        when(tb.registerExistingSecurityGroup(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("already registered"));
        when(tb.listSecurityGroupMetadata("default")).thenReturn(json("{\"securityGroup\":[]}"),json("""
            {"securityGroup":[{"id":"raced","connectionName":"aws-seoul","vNetId":"vnet","cspResourceId":"sg-cluster"}]}
            """));
        assertThat(resolver.resolve("default",cluster())).isEqualTo("raced");
    }
}
