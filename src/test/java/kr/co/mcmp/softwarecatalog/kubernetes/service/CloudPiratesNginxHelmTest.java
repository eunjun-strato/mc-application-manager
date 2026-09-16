package kr.co.mcmp.softwarecatalog.kubernetes.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kr.co.mcmp.softwarecatalog.application.dto.DeploymentConfigDTO;
import kr.co.mcmp.softwarecatalog.application.model.HelmChart;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "AM_HELM_BIN", matches = ".+")
class CloudPiratesNginxHelmTest {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({"false,nginx", "true,nginx", "false,public-iks-k8s-nginx", "true,public-iks-k8s-nginx"})
    void rendersTheActualAmIngressAdapterAndCidr(boolean tls, String ingressClass) throws Exception {
        var chart = HelmChart.builder().chartName("nginx").chartRepositoryUrl("https://cloudpirates-io.github.io/helm-charts").build();
        var config = DeploymentConfigDTO.builder().ingressEnabled(true).ingressHost("nginx.example.com")
                .servicePort(8088).ingressClass(ingressClass).ingressPath("/").ingressTlsEnabled(tls).ingressTlsSecret("nginx-tls").build();
        Map<String,Object> values = new HashMap<>(HelmIngressValues.from(chart, config));
        K8sIngressPolicy.configureValues("nginx", new HashMap<>(), values, config, "210.217.178.130/32");
        Path file = temp.resolve("values.yaml");
        Files.writeString(file, new Yaml().dump(values));
        Path output = temp.resolve("manifest.yaml");
        // Pull into an isolated directory: Helm otherwise prefers a local directory named nginx.
        Path pullLog = temp.resolve("pull.log");
        var pull = new ProcessBuilder(System.getenv("AM_HELM_BIN"), "pull", "nginx",
                "--repo", chart.getChartRepositoryUrl(), "--version", "0.16.8", "--destination", temp.toString())
                .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(pullLog.toFile()).start();
        assertThat(pull.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(pull.exitValue()).as(Files.readString(pullLog)).isZero();
        var process = new ProcessBuilder(System.getenv("AM_HELM_BIN"), "template", "nginx-test",
                temp.resolve("nginx-0.16.8.tgz").toString(),
                "--values", file.toString(), "--set", "autoscaling.enabled=true")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        String manifest = Files.readString(output);
        assertThat(process.exitValue()).as(manifest).isZero();
        K8sIngressPolicy.verifyManifest(manifest, "210.217.178.130/32", "nginx.example.com", ingressClass);
        assertThat(manifest).contains("kind: HorizontalPodAutoscaler", "nginx:1.31.5", "port: 8088")
                .doesNotContain("PersistentVolumeClaim");
        List<Map<String, Object>> resources = new ArrayList<>();
        for (Object document : new Yaml().loadAll(manifest)) {
            if (document instanceof Map<?, ?>) resources.add(asMap(document));
        }
        var deployment = resources.stream().filter(r -> "Deployment".equals(r.get("kind"))).findFirst().orElseThrow();
        var pod = asMap(asMap(asMap(deployment.get("spec")).get("template")).get("spec"));
        var container = asMap(((List<?>) pod.get("containers")).get(0));
        var ports = (List<?>) container.get("ports");
        assertThat(ports).hasSize(1);
        assertThat(asMap(ports.get(0))).containsEntry("containerPort", 8080).containsEntry("name", "http");
        assertThat(asMap(container.get("securityContext"))).containsEntry("runAsUser", 101)
                .containsEntry("runAsNonRoot", true).containsEntry("allowPrivilegeEscalation", false);
        var service = resources.stream().filter(r -> "Service".equals(r.get("kind"))).findFirst().orElseThrow();
        var servicePort = asMap(((List<?>) asMap(service.get("spec")).get("ports")).get(0));
        assertThat(servicePort).containsEntry("port", 8088).containsEntry("targetPort", "http");
        var serverConfig = resources.stream().filter(r -> "ConfigMap".equals(r.get("kind")))
                .flatMap(r -> asMap(r.get("data")).values().stream()).map(String::valueOf)
                .filter(value -> value.contains("server_name")).findFirst().orElseThrow();
        assertThat(serverConfig).contains("listen 8080;").doesNotContain("listen 80;");
        if (tls) assertThat(manifest).contains("secretName: nginx-tls");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
