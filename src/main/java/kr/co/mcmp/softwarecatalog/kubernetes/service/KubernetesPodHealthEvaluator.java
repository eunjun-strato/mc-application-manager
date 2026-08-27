package kr.co.mcmp.softwarecatalog.kubernetes.service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;

/**
 * Converts Kubernetes Pod readiness and container runtime states to the nullable
 * Boolean stored by {@code ApplicationStatus}.
 *
 * <p>{@code true} means all active application Pods are ready, {@code false}
 * means Kubernetes reported an explicit failure (or a running Pod stayed
 * unready beyond the startup grace period), and {@code null} means that the
 * workload is still being prepared or cannot yet be evaluated.</p>
 */
final class KubernetesPodHealthEvaluator {

    private static final Set<String> FAILURE_WAITING_REASONS = Set.of(
            "crashloopbackoff",
            "imagepullbackoff",
            "errimagepull",
            "createcontainerconfigerror",
            "createcontainererror",
            "runcontainererror",
            "invalidimagename");

    private KubernetesPodHealthEvaluator() {
    }

    static Boolean evaluate(List<Pod> pods, Duration startupGrace, Instant now) {
        if (pods == null || pods.isEmpty()) {
            return null;
        }

        Duration effectiveGrace = startupGrace == null || startupGrace.isNegative()
                ? Duration.ZERO
                : startupGrace;
        Instant effectiveNow = now != null ? now : Instant.now();

        List<Pod> activePods = pods.stream()
                .filter(KubernetesPodHealthEvaluator::isActiveApplicationPod)
                .toList();
        if (activePods.isEmpty()) {
            return null;
        }

        boolean allReady = true;
        for (Pod pod : activePods) {
            if (isPodReady(pod)) {
                continue;
            }

            allReady = false;
            if (hasExplicitFailure(pod)) {
                return false;
            }

            if (isRunning(pod) && !isWithinStartupGrace(pod, effectiveGrace, effectiveNow)) {
                return false;
            }
        }

        return allReady ? Boolean.TRUE : null;
    }

    private static boolean isActiveApplicationPod(Pod pod) {
        if (pod == null) {
            return false;
        }
        if (pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() != null) {
            return false;
        }
        return !"SUCCEEDED".equalsIgnoreCase(phase(pod));
    }

    private static boolean isPodReady(Pod pod) {
        if (!isRunning(pod) || pod.getStatus() == null) {
            return false;
        }

        boolean readyCondition = safeList(pod.getStatus().getConditions()).stream()
                .anyMatch(KubernetesPodHealthEvaluator::isReadyCondition);
        if (!readyCondition) {
            return false;
        }

        List<ContainerStatus> statuses = safeList(pod.getStatus().getContainerStatuses());
        int expectedContainers = pod.getSpec() != null && pod.getSpec().getContainers() != null
                ? pod.getSpec().getContainers().size()
                : 0;
        return expectedContainers > 0
                && statuses.size() >= expectedContainers
                && statuses.stream().allMatch(status -> Boolean.TRUE.equals(status.getReady()));
    }

    private static boolean isReadyCondition(PodCondition condition) {
        return condition != null
                && "READY".equalsIgnoreCase(condition.getType())
                && "TRUE".equalsIgnoreCase(condition.getStatus());
    }

    private static boolean hasExplicitFailure(Pod pod) {
        if ("FAILED".equalsIgnoreCase(phase(pod))) {
            return true;
        }
        if (pod.getStatus() == null) {
            return false;
        }

        List<ContainerStatus> statuses = new ArrayList<>();
        statuses.addAll(safeList(pod.getStatus().getInitContainerStatuses()));
        statuses.addAll(safeList(pod.getStatus().getContainerStatuses()));
        return statuses.stream().anyMatch(KubernetesPodHealthEvaluator::hasExplicitFailure);
    }

    private static boolean hasExplicitFailure(ContainerStatus status) {
        if (status == null) {
            return false;
        }

        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = normalize(status.getState().getWaiting().getReason());
            if (FAILURE_WAITING_REASONS.contains(reason)) {
                return true;
            }
        }

        if (status.getState() != null && status.getState().getTerminated() != null) {
            Integer exitCode = status.getState().getTerminated().getExitCode();
            if ("OOMKILLED".equalsIgnoreCase(status.getState().getTerminated().getReason())
                    || (exitCode != null && exitCode != 0)) {
                return true;
            }
        }

        return !Boolean.TRUE.equals(status.getReady())
                && status.getLastState() != null
                && status.getLastState().getTerminated() != null
                && "OOMKILLED".equalsIgnoreCase(status.getLastState().getTerminated().getReason());
    }

    private static boolean isRunning(Pod pod) {
        return "RUNNING".equalsIgnoreCase(phase(pod));
    }

    private static String phase(Pod pod) {
        return pod != null && pod.getStatus() != null ? pod.getStatus().getPhase() : null;
    }

    private static boolean isWithinStartupGrace(Pod pod, Duration startupGrace, Instant now) {
        Instant startedAt = parseInstant(pod.getStatus() != null ? pod.getStatus().getStartTime() : null);
        if (startedAt == null && pod.getMetadata() != null) {
            startedAt = parseInstant(pod.getMetadata().getCreationTimestamp());
        }
        return startedAt == null || now.isBefore(startedAt.plus(startupGrace));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values != null ? values : List.of();
    }
}
