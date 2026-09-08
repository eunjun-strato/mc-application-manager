package kr.co.mcmp.softwarecatalog.kubernetes.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;

/** The SSH listener and reverse Gateway bind only to this Pod's loopback. */
final class K8sSshSidecar {
    static final String DEFAULT_IMAGE = "lscr.io/linuxserver/openssh-server@sha256:39ba37d50fdd6be1bf70644c871e5dcb9234ee79ac56424ea03ca08cadf1e7b0";
    private K8sSshSidecar() { }
    static ConfigMap configuration(String name, String namespace, java.util.Map<String,String> labels) throws IOException {
        var data = new LinkedHashMap<String,String>();
        for (String file : new String[]{"start.sh", "health.sh", "sshd_config", "passwd", "shadow", "group"}) {
            try (var input = new ClassPathResource("k8s-ssh/" + file).getInputStream()) {
                data.put(file, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new ConfigMapBuilder().withNewMetadata().withName(name).withNamespace(namespace).withLabels(labels)
                .endMetadata().withImmutable(true).withData(data).build();
    }
    static void attach(Deployment deployment, String image, String secretName) {
        if (image == null || image.isBlank()) throw new IllegalArgumentException("Configure OBJECT_STORAGE_K8S_SSH_IMAGE with a compatible LinuxServer OpenSSH image.");
        var spec=deployment.getSpec().getTemplate().getSpec();
        spec.getVolumes().add(new VolumeBuilder().withName("ssh-credentials").withNewSecret()
                .withSecretName(secretName).withDefaultMode(0400)
                .withItems(new KeyToPathBuilder().withKey("host-key").withPath("host-key").build(),
                        new KeyToPathBuilder().withKey("authorized_keys").withPath("authorized_keys").build()).endSecret().build());
        spec.getVolumes().add(new VolumeBuilder().withName("ssh-runtime").withNewEmptyDir().withMedium("Memory").endEmptyDir().build());
        spec.getVolumes().add(new VolumeBuilder().withName("ssh-config").withNewConfigMap()
                .withName(secretName).withDefaultMode(0444).endConfigMap().build());
        var mounts = new ArrayList<VolumeMount>();
        mounts.add(new VolumeMountBuilder().withName("ssh-credentials").withMountPath("/etc/mcmp-ssh").withReadOnly(true).build());
        mounts.add(new VolumeMountBuilder().withName("ssh-runtime").withMountPath("/run/mcmp-ssh").build());
        mounts.add(new VolumeMountBuilder().withName("ssh-config").withMountPath("/opt/mcmp-ssh").withReadOnly(true).build());
        // Use an isolated, password-disabled account database without modifying the public image.
        for (String file : new String[]{"passwd", "shadow", "group"})
            mounts.add(new VolumeMountBuilder().withName("ssh-config").withMountPath("/etc/" + file).withSubPath(file).withReadOnly(true).build());
        spec.getContainers().add(new ContainerBuilder().withName("ssh-tunnel").withImage(image)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", "/opt/mcmp-ssh/start.sh").withVolumeMounts(mounts)
                .withNewResources().addToRequests("cpu", new Quantity("10m")).addToRequests("memory", new Quantity("32Mi"))
                        .addToLimits("cpu", new Quantity("200m")).addToLimits("memory", new Quantity("128Mi")).endResources()
                .withNewSecurityContext().withRunAsUser(0L).withRunAsGroup(0L).withAllowPrivilegeEscalation(false).withReadOnlyRootFilesystem(true)
                        .withNewCapabilities().withDrop("ALL").withAdd("SETUID", "SETGID", "SYS_CHROOT", "CHOWN").endCapabilities()
                        .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile().endSecurityContext()
                .withNewReadinessProbe().withNewExec().withCommand("/bin/sh", "/opt/mcmp-ssh/health.sh").endExec()
                        .withPeriodSeconds(5).withTimeoutSeconds(4).withFailureThreshold(2).endReadinessProbe().build());
        // No container port, Service, NodePort or hostPort is published for SSH.
    }
}
