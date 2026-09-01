package kr.co.mcmp.softwarecatalog.kubernetes.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;

class KubernetesPodHealthEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-27T06:30:00Z");
    private static final Duration STARTUP_GRACE = Duration.ofMinutes(3);

    @Test
    void returnsUnknownValueWhenNoPodsCanBeEvaluated() {
        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(), STARTUP_GRACE, NOW)).isNull();
    }

    @Test
    void reportsHealthyWhenEveryActivePodAndContainerIsReady() {
        Pod first = readyPod("app-1", NOW.minusSeconds(600));
        Pod second = readyPod("app-2", NOW.minusSeconds(600));

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(first, second), STARTUP_GRACE, NOW))
                .isTrue();
    }

    @Test
    void reportsCheckingForAContainerThatIsStillBeingCreated() {
        Pod pod = waitingPod("app", "Pending", "ContainerCreating", NOW.minusSeconds(30));

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(pod), STARTUP_GRACE, NOW)).isNull();
    }

    @Test
    void reportsCheckingForRunningButUnreadyPodWithinStartupGrace() {
        Pod pod = runningUnreadyPod("app", NOW.minusSeconds(60));

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(pod), STARTUP_GRACE, NOW)).isNull();
    }

    @Test
    void reportsUnhealthyWhenRunningPodStaysUnreadyBeyondStartupGrace() {
        Pod pod = runningUnreadyPod("app", NOW.minusSeconds(181));

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(pod), STARTUP_GRACE, NOW)).isFalse();
    }

    @Test
    void reportsUnhealthyImmediatelyForCrashLoopBackOff() {
        Pod pod = waitingPod("app", "Running", "CrashLoopBackOff", NOW.minusSeconds(20));

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(pod), STARTUP_GRACE, NOW)).isFalse();
    }

    @Test
    void reportsUnhealthyImmediatelyForImagePullBackOff() {
        Pod pod = waitingPod("app", "Pending", "ImagePullBackOff", NOW.minusSeconds(20));

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(pod), STARTUP_GRACE, NOW)).isFalse();
    }

    @Test
    void reportsUnhealthyForAnOomKilledContainerThatIsNotReady() {
        Pod pod = new PodBuilder(runningUnreadyPod("app", NOW.minusSeconds(20)))
                .editStatus()
                    .editFirstContainerStatus()
                        .withRestartCount(4)
                        .withNewLastState()
                            .withNewTerminated()
                                .withReason("OOMKilled")
                                .withExitCode(137)
                            .endTerminated()
                        .endLastState()
                    .endContainerStatus()
                .endStatus()
                .build();

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(pod), STARTUP_GRACE, NOW)).isFalse();
    }

    @Test
    void reportsUnhealthyWhenAnyReplicaIsUnreadyBeyondGrace() {
        Pod ready = readyPod("app-1", NOW.minusSeconds(600));
        Pod unready = runningUnreadyPod("app-2", NOW.minusSeconds(600));

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(ready, unready), STARTUP_GRACE, NOW))
                .isFalse();
    }

    @Test
    void ignoresTerminatingReplicaDuringAReplacement() {
        Pod ready = readyPod("app-new", NOW.minusSeconds(600));
        Pod terminating = new PodBuilder(runningUnreadyPod("app-old", NOW.minusSeconds(600)))
                .editMetadata()
                    .withDeletionTimestamp(NOW.minusSeconds(10).toString())
                .endMetadata()
                .build();

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(ready, terminating), STARTUP_GRACE, NOW))
                .isTrue();
    }

    @Test
    void reportsHealthyAfterAPreviouslyOomKilledContainerRecovers() {
        Pod recovered = new PodBuilder(readyPod("app", NOW.minusSeconds(600)))
                .editStatus()
                    .editFirstContainerStatus()
                        .withRestartCount(1)
                        .withNewLastState()
                            .withNewTerminated()
                                .withReason("OOMKilled")
                                .withExitCode(137)
                            .endTerminated()
                        .endLastState()
                    .endContainerStatus()
                .endStatus()
                .build();

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(recovered), STARTUP_GRACE, NOW)).isTrue();
    }

    @Test
    void reportsUnhealthyForFailedPodPhase() {
        Pod failed = new PodBuilder(runningUnreadyPod("app", NOW.minusSeconds(20)))
                .editStatus()
                    .withPhase("Failed")
                .endStatus()
                .build();

        assertThat(KubernetesPodHealthEvaluator.evaluate(List.of(failed), STARTUP_GRACE, NOW)).isFalse();
    }

    private static Pod readyPod(String name, Instant startedAt) {
        return new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withCreationTimestamp(startedAt.toString())
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("example/app:1")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                    .withStartTime(startedAt.toString())
                    .addNewCondition()
                        .withType("Ready")
                        .withStatus("True")
                    .endCondition()
                    .addNewContainerStatus()
                        .withName("app")
                        .withImage("example/app:1")
                        .withImageID("example/app@sha256:abc")
                        .withReady(true)
                        .withRestartCount(0)
                        .withNewState()
                            .withNewRunning()
                                .withStartedAt(startedAt.toString())
                            .endRunning()
                        .endState()
                    .endContainerStatus()
                .endStatus()
                .build();
    }

    private static Pod runningUnreadyPod(String name, Instant startedAt) {
        return new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withCreationTimestamp(startedAt.toString())
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("example/app:1")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                    .withStartTime(startedAt.toString())
                    .addNewCondition()
                        .withType("Ready")
                        .withStatus("False")
                    .endCondition()
                    .addNewContainerStatus()
                        .withName("app")
                        .withImage("example/app:1")
                        .withImageID("example/app@sha256:abc")
                        .withReady(false)
                        .withRestartCount(0)
                        .withNewState()
                            .withNewRunning()
                                .withStartedAt(startedAt.toString())
                            .endRunning()
                        .endState()
                    .endContainerStatus()
                .endStatus()
                .build();
    }

    private static Pod waitingPod(String name, String phase, String reason, Instant startedAt) {
        return new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withCreationTimestamp(startedAt.toString())
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("example/app:1")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase(phase)
                    .withStartTime(startedAt.toString())
                    .addNewCondition()
                        .withType("Ready")
                        .withStatus("False")
                    .endCondition()
                    .addNewContainerStatus()
                        .withName("app")
                        .withImage("example/app:1")
                        .withImageID("")
                        .withReady(false)
                        .withRestartCount(0)
                        .withNewState()
                            .withNewWaiting()
                                .withReason(reason)
                            .endWaiting()
                        .endState()
                    .endContainerStatus()
                .endStatus()
                .build();
    }
}
