package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageTunnel;
import kr.co.mcmp.softwarecatalog.application.repository.ObjectStorageTunnelRepository;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;

class ObjectStorageTunnelServiceTest {
    ObjectStorageTunnelRepository repository;
    ObjectStorageTunnelRuntime runtime;
    ObjectStorageTunnelService service;
    ObjectStorageTunnel record;
    ObjectStorageTunnelRuntime.Running process;
    DockerTarget target = new DockerTarget("default", "infra-1", "g1-1");
    String cid = "a".repeat(64);

    @BeforeEach void setup() {
        repository = mock(ObjectStorageTunnelRepository.class);
        runtime = mock(ObjectStorageTunnelRuntime.class);
        process = mock(ObjectStorageTunnelRuntime.Running.class);
        service = new ObjectStorageTunnelService(repository, runtime);
        when(repository.findByDeploymentId(anyLong())).thenAnswer(i -> Optional.ofNullable(record));
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(record));
        when(repository.findByDesiredStateNot(anyString())).thenAnswer(i -> record == null ? List.of() : List.of(record));
        when(repository.findByNamespaceAndMciIdAndVmIdAndContainerId(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(i -> Optional.ofNullable(record));
        when(repository.saveAndFlush(any())).thenAnswer(i -> { record = i.getArgument(0); return record; });
        when(repository.claim(anyString(),anyString(),any(),any())).thenAnswer(i -> {
            record.setLeaseOwner(i.getArgument(1));
            record.setLeaseUntil(i.getArgument(3));
            return 1;
        });
        doAnswer(i -> { record.setDesiredState(i.getArgument(1)); return null; })
                .when(repository).desire(anyString(),anyString(),any());
        doAnswer(i -> { record.setStatus(i.getArgument(2)); return null; })
                .when(repository).status(anyString(),anyString(),anyString(),any());
        when(runtime.start(any())).thenReturn(process);
        when(process.alive()).thenReturn(true);
        when(runtime.healthy(any(),anyBoolean())).thenReturn(true);
    }

    @AfterEach void close() { service.close(); }

    @Test void installsUsingLoopbackAndVerifiesStorageBeforeReady() {
        assertThat(service.gatewayUrl()).isEqualTo("http://127.0.0.1:18084/applications/object-storage-gateway");
        service.install(1L,target,cid);
        assertThat(record.getStatus()).isEqualTo("READY");
        var order = inOrder(runtime);
        order.verify(runtime).start(record);
        order.verify(runtime).healthy(record,true);
    }

    @Test void directModeDoesNotCreateTunnel() {
        ReflectionTestUtils.setField(service,"transport","DIRECT");
        ReflectionTestUtils.setField(service,"publicBaseUrl","https://am.example/");
        assertThat(service.gatewayUrl()).isEqualTo("https://am.example/applications/object-storage-gateway");
        service.install(1L,target,cid);
        verifyNoInteractions(runtime);
        assertThat(record).isNull();
    }

    @Test void failedSetupRollsBackAndNeverMarksReady() {
        when(runtime.start(any())).thenThrow(new IllegalStateException("SSH unavailable"));
        assertThatThrownBy(() -> service.install(1L,target,cid)).hasMessageContaining("SSH unavailable");
        assertThat(record.getDesiredState()).isEqualTo("DELETED");
        verify(runtime).remove(record);
        verify(repository,never()).status(anyString(),anyString(),eq("READY"),any());
    }

    @Test void failedCleanupRemainsPendingAndRetries() {
        service.install(1L,target,cid);
        doThrow(new IllegalStateException("offline")).doNothing().when(runtime).remove(any());
        assertThatThrownBy(() -> service.remove(1L)).hasMessageContaining("offline");
        assertThat(record.getDesiredState()).isEqualTo("DELETE_PENDING");
        verify(process).close();
        service.reconcile();
        assertThat(record.getDesiredState()).isEqualTo("DELETED");
        verify(runtime,times(2)).remove(record);
    }

    @Test void stopPreventsReconnectAndResumeCreatesNewBridge() {
        service.install(1L,target,cid);
        service.suspend(target,cid);
        assertThat(record.getDesiredState()).isEqualTo("STOPPED");
        service.reconcile();
        verify(runtime,times(1)).start(any());
        service.resume(target,cid);
        verify(runtime,times(2)).start(any());
        assertThat(record.getStatus()).isEqualTo("READY");
    }

    @Test void amRestartRestoresActiveDesiredState() {
        service.install(1L,target,cid);
        service.close();
        assertThat(record.getDesiredState()).isEqualTo("ACTIVE");
        service = new ObjectStorageTunnelService(repository,runtime);
        service.reconcile();
        verify(runtime,times(2)).start(any());
        assertThat(record.getStatus()).isEqualTo("READY");
    }

    @Test void deadSshProcessReconnectsAndRevalidatesStorage() {
        service.install(1L,target,cid);
        when(process.alive()).thenReturn(false,true,true);
        service.reconcile();
        verify(process).close();
        verify(runtime,times(2)).start(any());
        verify(runtime,times(2)).healthy(any(),eq(true));
    }

    @Test void anotherAmLeasePreventsRemoteMutation() {
        service.install(1L,target,cid);
        clearInvocations(runtime);
        when(repository.claim(anyString(),anyString(),any(),any())).thenReturn(0);
        service.reconcile();
        verify(process).close();
        verifyNoInteractions(runtime);
    }

    @Test void lostLeaseClosesLocalSsh() {
        service.install(1L,target,cid);
        when(repository.renew(anyString(),any(),any())).thenReturn(0);
        service.heartbeat();
        verify(process).close();
    }

    @Test void doesNotAdoptDifferentContainerForSameDeployment() {
        service.install(1L,target,cid);
        assertThatThrownBy(() -> service.install(1L,target,"b".repeat(64)))
                .hasMessageContaining("different tunnel");
        verify(runtime,times(1)).start(any());
    }

    @Test void missingLegacyTunnelIsNoOp() {
        service.remove(42L);
        verifyNoInteractions(runtime);
    }

    @Test void invalidTransportFailsClearly() {
        ReflectionTestUtils.setField(service,"transport","unknown");
        assertThatThrownBy(service::gatewayUrl).hasMessageContaining("Configure SSH_TUNNEL or DIRECT");
    }
}
