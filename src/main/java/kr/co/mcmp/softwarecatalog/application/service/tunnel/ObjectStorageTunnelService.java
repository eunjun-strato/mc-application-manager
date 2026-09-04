package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PreDestroy;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageTunnel;
import kr.co.mcmp.softwarecatalog.application.repository.ObjectStorageTunnelRepository;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Durable lifecycle with a renewable DB lease, including retryable remote cleanup. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ObjectStorageTunnelService {
    static final long LEASE_SECONDS = 120;
    private final ObjectStorageTunnelRepository repository;
    private final ObjectStorageTunnelRuntime runtime;
    private final String owner = UUID.randomUUID().toString();
    private final ConcurrentMap<String, ObjectStorageTunnelRuntime.Running> running = new ConcurrentHashMap<>();
    private volatile Instant lastLeaseRefresh = Instant.now();
    private volatile boolean closed;
    private ScheduledExecutorService scheduler;
    @Value("${app.object-storage.transport:SSH_TUNNEL}")
    private String transport = "SSH_TUNNEL";
    @Value("${app.object-storage.gateway-public-base-url:}")
    private String publicBaseUrl = "";
    @Value("${app.scheduling.enabled:true}")
    private boolean reconcileEnabled = true;

    public String gatewayUrl() {
        if ("SSH_TUNNEL".equalsIgnoreCase(transport)) {
            return "http://127.0.0.1:18084" + ObjectStorageTunnelProxy.PREFIX;
        }
        if (!"DIRECT".equalsIgnoreCase(transport) || publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException("Configure SSH_TUNNEL or DIRECT with a reachable Object Storage gateway URL.");
        }
        return publicBaseUrl.trim().replaceAll("/+$", "") + ObjectStorageTunnelProxy.PREFIX;
    }

    public synchronized void install(Long deploymentId, DockerTarget target, String containerId) {
        if (!"SSH_TUNNEL".equalsIgnoreCase(transport)) return;
        if (closed) throw new IllegalStateException("AM is shutting down");
        ObjectStorageTunnel tunnel = repository.findByDeploymentId(deploymentId).orElseGet(() -> {
            ObjectStorageTunnel created = new ObjectStorageTunnel();
            created.setId(UUID.randomUUID().toString().replace("-", ""));
            created.setDeploymentId(deploymentId);
            created.setNamespace(target.namespace());
            created.setMciId(target.mciId());
            created.setVmId(target.vmId());
            created.setContainerId(containerId);
            created.setLeaseOwner(owner);
            created.setLeaseUntil(Instant.now().plusSeconds(LEASE_SECONDS));
            created.setUpdatedAt(Instant.now());
            return repository.saveAndFlush(created);
        });
        if (!containerId.equals(tunnel.getContainerId()) || !target.equals(target(tunnel))
                || !"ACTIVE".equals(tunnel.getDesiredState())) {
            throw new IllegalStateException("A different tunnel already exists for this deployment");
        }
        try {
            reconcileOne(tunnel, true);
        } catch (RuntimeException failure) {
            repository.desire(tunnel.getId(), "DELETE_PENDING", Instant.now());
            try { reconcileOne(tunnel, false); } catch (RuntimeException ignored) {
                log.warn("Tunnel cleanup queued for deployment {}", deploymentId);
            }
            throw failure;
        }
    }

    public synchronized void remove(Long deploymentId) {
        if (deploymentId == null) return;
        repository.findByDeploymentId(deploymentId).ifPresent(t -> change(t, "DELETE_PENDING"));
    }

    public synchronized void remove(DockerTarget target, String containerId) {
        find(target, containerId).ifPresent(t -> change(t, "DELETE_PENDING"));
    }

    public synchronized void suspend(DockerTarget target, String containerId) {
        find(target, containerId).ifPresent(t -> change(t, "STOPPED"));
    }

    public synchronized void resume(DockerTarget target, String containerId) {
        find(target, containerId).ifPresent(t -> {
            if (!"DELETED".equals(t.getDesiredState()) && !"DELETE_PENDING".equals(t.getDesiredState())) change(t, "ACTIVE");
        });
    }

    private Optional<ObjectStorageTunnel> find(DockerTarget target, String containerId) {
        return repository.findByNamespaceAndMciIdAndVmIdAndContainerId(
                target.namespace(), target.mciId(), target.vmId(), containerId);
    }

    private void change(ObjectStorageTunnel tunnel, String state) {
        if ("DELETED".equals(tunnel.getDesiredState())) return;
        repository.desire(tunnel.getId(), state, Instant.now()); // Commit before external side effects.
        reconcileOne(tunnel, "ACTIVE".equals(state));
    }

    private void reconcileOne(ObjectStorageTunnel prior, boolean verifyStorage) {
        Instant now = Instant.now();
        if (repository.claim(prior.getId(), owner, now, now.plusSeconds(LEASE_SECONDS)) != 1) {
            stopLocal(prior.getId());
            throw new IllegalStateException("Tunnel lifecycle change is queued on its active AM owner; retry shortly.");
        }
        ObjectStorageTunnel tunnel = repository.findById(prior.getId()).orElseThrow();
        if ("DELETED".equals(tunnel.getDesiredState())) { stopLocal(tunnel.getId()); return; }
        try {
            if (!"ACTIVE".equals(tunnel.getDesiredState())) {
                if ("STOPPED".equals(tunnel.getDesiredState()) && "STOPPED".equals(tunnel.getStatus())
                        && !running.containsKey(tunnel.getId())) return;
                stopLocal(tunnel.getId());
                runtime.remove(tunnel);
                if ("DELETE_PENDING".equals(tunnel.getDesiredState())) {
                    repository.desire(tunnel.getId(), "DELETED", Instant.now());
                    status(tunnel, "DELETED");
                } else status(tunnel, "STOPPED");
                return;
            }
            var process = running.get(tunnel.getId());
            if (process == null || !process.alive() || !runtime.healthy(tunnel, false)) {
                stopLocal(tunnel.getId());
                status(tunnel, "STARTING");
                process = runtime.start(tunnel);
                running.put(tunnel.getId(), process);
                verifyStorage = true;
            }
            if (verifyStorage) {
                boolean ready = false;
                for (int attempt = 0; attempt < 4 && process.alive(); attempt++) {
                    if (runtime.healthy(tunnel, true)) { ready = true; break; }
                    try { Thread.sleep(1000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Tunnel setup interrupted");
                    }
                }
                if (!ready) throw new IllegalStateException("Jupyter cannot list Object Storage through the SSH tunnel.");
            }
            // Another AM may have queued stop/delete while this instance was connecting.
            if (!"ACTIVE".equals(repository.findById(tunnel.getId()).orElseThrow().getDesiredState())) {
                stopLocal(tunnel.getId());
                throw new IllegalStateException("Tunnel lifecycle changed during setup");
            }
            status(tunnel, "READY");
        } catch (RuntimeException e) {
            status(tunnel, "ERROR");
            throw e;
        }
    }

    private void status(ObjectStorageTunnel tunnel, String status) {
        repository.status(tunnel.getId(), owner, status, Instant.now());
    }

    private DockerTarget target(ObjectStorageTunnel tunnel) {
        return new DockerTarget(tunnel.getNamespace(), tunnel.getMciId(), tunnel.getVmId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startReconciler() {
        if (scheduler != null || closed) return;
        scheduler = Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "object-storage-tunnel-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        // Heartbeat is independent from potentially slow VM calls.
        scheduler.scheduleWithFixedDelay(this::heartbeat, 15, 15, TimeUnit.SECONDS);
        if (reconcileEnabled) scheduler.scheduleWithFixedDelay(this::reconcile, 5, 30, TimeUnit.SECONDS);
    }

    void heartbeat() {
        if (running.isEmpty() || closed) return;
        Instant now = Instant.now();
        try {
            int renewed = repository.renew(owner, now, now.plusSeconds(LEASE_SECONDS));
            if (renewed == 0) running.keySet().forEach(this::stopLocal);
            lastLeaseRefresh = now;
        } catch (RuntimeException e) {
            // Fail closed before the lease can be taken over by another AM.
            if (lastLeaseRefresh.plusSeconds(LEASE_SECONDS - 15).isBefore(now)) {
                running.keySet().forEach(this::stopLocal);
            }
        }
    }

    synchronized void reconcile() {
        if (closed) return;
        try {
            for (ObjectStorageTunnel tunnel : repository.findByDesiredStateNot("DELETED")) {
                try { reconcileOne(tunnel, false); }
                catch (RuntimeException e) {
                    log.warn("Object Storage tunnel not ready; will retry deployment {}", tunnel.getDeploymentId());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Object Storage tunnel reconciliation deferred; database unavailable");
        }
    }

    private void stopLocal(String id) {
        var process = running.remove(id);
        if (process != null) process.close();
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (scheduler != null) scheduler.shutdownNow();
        running.keySet().forEach(this::stopLocal);
        try { repository.release(owner); } catch (RuntimeException ignored) { }
        // Desired ACTIVE records remain for recovery after AM restart.
    }
}
