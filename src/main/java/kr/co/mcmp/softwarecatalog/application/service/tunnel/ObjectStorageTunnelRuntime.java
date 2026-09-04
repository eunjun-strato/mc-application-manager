package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.VmAccessInfo;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageTunnel;
import kr.co.mcmp.softwarecatalog.application.repository.ObjectStorageTunnelRepository;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;
import kr.co.mcmp.softwarecatalog.docker.service.DockerSshCommandExecutor;
import lombok.RequiredArgsConstructor;

/** Starts one OpenSSH child per deployment, not an externally managed host service. */
@Component
@RequiredArgsConstructor
public class ObjectStorageTunnelRuntime {
    private final CbtumblebugRestApi tumblebug;
    private final DockerSshCommandExecutor commands;
    private final ObjectMapper mapper;
    private final ObjectStorageTunnelProxy proxy;
    private final ObjectStorageTunnelRepository repository;
    @Value("${app.object-storage.tunnel.ssh-executable:ssh}")
    private String sshExecutable = "ssh";

    public Running start(ObjectStorageTunnel tunnel) {
        Path directory = null;
        Process process = null;
        try {
            VmAccessInfo vm = vm(tunnel);
            JsonNode info = remote(tunnel, "probe");
            if (!info.path("running").asBoolean()) throw new IllegalStateException("Jupyter is not running");
            String hostKey = info.path("hostKey").asText();
            String user = info.path("user").asText();
            if (!hostKey.matches("ssh-ed25519 [A-Za-z0-9+/=]+") || !user.matches("[a-z_][a-z0-9_-]*[$]?")) {
                throw new IllegalStateException("Invalid SSH identity");
            }
            if (tunnel.getHostKey() != null && !tunnel.getHostKey().equals(hostKey)) {
                throw new IllegalStateException("SSH host key changed; manual verification required");
            }
            String vmUid = identity(vm);
            repository.identity(tunnel.getId(), tunnel.getLeaseOwner(), vmUid, hostKey);
            tunnel.setVmUid(vmUid);
            tunnel.setHostKey(hostKey);

            // Query ONLY the selected VM key; never request every namespace credential.
            var key = tumblebug.getSshKey(tunnel.getNamespace(), vm.getSshKeyId());
            if (key == null || key.getPrivateKey() == null || !key.getPrivateKey().startsWith("-----BEGIN ")) {
                throw new IllegalStateException("The VM SSH private key is unavailable");
            }
            directory = privateDirectory();
            Files.writeString(directory.resolve("key"), key.getPrivateKey().strip() + "\n", StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("known_hosts"), "mcmp-" + tunnel.getId() + " " + hostKey + "\n");
            Files.writeString(directory.resolve("config"), "");
            for (String name : List.of("key", "known_hosts", "config")) privatePermissions(directory.resolve(name), false);

            remote(tunnel, "attach");
            process = new ProcessBuilder(sshArguments(sshExecutable, tunnel.getId(), vm.getPublicIP(),
                    sshPort(vm.getSshPort()), user, directory, proxy.port()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return new Running(process, directory);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            erase(directory);
            // Do not propagate command arguments, private key text or remote response bodies.
            throw new IllegalStateException("Cannot establish managed Object Storage SSH tunnel. "
                    + "Check AM-to-VM SSH access, OpenSSH stream-local forwarding and the registered VM key.", e instanceof IllegalStateException ? e : null);
        }
    }

    static List<String> sshArguments(String executable, String id, String ip, int port, String user,
                                    Path directory, int proxyPort) {
        if (!id.matches("[a-f0-9]{32}") || ip == null || !ip.matches("[0-9A-Fa-f:.]+")
                || !user.matches("[a-z_][a-z0-9_-]*[$]?") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid SSH connection parameters");
        }
        String knownHosts = directory.resolve("known_hosts").toAbsolutePath().toString().replace('\\', '/');
        return List.of(executable, "-F", directory.resolve("config").toString(), "-N", "-T", "-n",
                "-i", directory.resolve("key").toString(),
                "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                "-o", "UserKnownHostsFile=\"" + knownHosts + "\"", "-o", "HostKeyAlias=mcmp-" + id,
                "-o", "CheckHostIP=no", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=3", "-o", "ExitOnForwardFailure=yes",
                "-o", "StreamLocalBindMask=0177",
                "-p", String.valueOf(port), "-l", user, "-R",
                "/tmp/mcmp-os-" + id + "/gateway.sock:127.0.0.1:" + proxyPort, ip);
    }

    private int sshPort(String value) {
        return value == null || value.isBlank() ? 22 : Integer.parseInt(value);
    }

    public boolean healthy(ObjectStorageTunnel tunnel, boolean verifyStorage) {
        return remote(tunnel, verifyStorage ? "verify" : "check").path("ready").asBoolean();
    }

    public void remove(ObjectStorageTunnel tunnel) {
        remote(tunnel, "remove");
    }

    private VmAccessInfo vm(ObjectStorageTunnel tunnel) {
        VmAccessInfo vm = tumblebug.getVmInfo(tunnel.getNamespace(), tunnel.getMciId(), tunnel.getVmId());
        if (vm == null || (tunnel.getVmUid() != null && !tunnel.getVmUid().equals(identity(vm)))) {
            throw new IllegalStateException("VM identity changed; refusing tunnel mutation");
        }
        return vm;
    }

    private String identity(VmAccessInfo vm) {
        String value = vm.getUid();
        if (value == null || value.isBlank()) value = vm.getCspResourceId();
        if (value == null || value.isBlank()) throw new IllegalStateException("Stable VM identity unavailable");
        return value;
    }

    private JsonNode remote(ObjectStorageTunnel tunnel, String action) {
        vm(tunnel); // Do not clean up artifacts on a replacement VM sharing a logical name.
        try {
            Map<String, Object> data = Map.of("id", tunnel.getId(), "container", tunnel.getContainerId(),
                    "deployment", tunnel.getDeploymentId(), "action", action);
            String script = "python3 - <<'MCMP_TUNNEL_HELPER'\nimport base64,json\nDATA=json.loads(base64.b64decode('"
                    + Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(data)) + "'))\n"
                    + "BRIDGE_CODE=base64.b64decode('"
                    + Base64.getEncoder().encodeToString(resource("tunnel/bridge.py").getBytes(StandardCharsets.UTF_8))
                    + "').decode('utf-8')\n" + resource("tunnel/remote.py") + "\nMCMP_TUNNEL_HELPER\n";
            var output = commands.execute(new DockerTarget(tunnel.getNamespace(), tunnel.getMciId(), tunnel.getVmId()), script);
            return mapper.readTree(output.stdout());
        } catch (Exception e) {
            throw new IllegalStateException("Managed Object Storage tunnel VM operation failed (" + action + ")");
        }
    }

    private String resource(String name) throws IOException {
        try (var stream = new ClassPathResource(name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Path privateDirectory() throws IOException {
        Path directory = Files.createTempDirectory("mcmp-object-storage-");
        try {
            privatePermissions(directory, true);
            return directory;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(directory);
            throw e;
        }
    }

    private static void privatePermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Private file permissions are unsupported");
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
    }

    private static void erase(Path directory) {
        if (directory == null) return;
        for (String name : List.of("key", "known_hosts", "config")) {
            try { Files.deleteIfExists(directory.resolve(name)); } catch (IOException ignored) { }
        }
        try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
    }

    public static class Running implements AutoCloseable {
        private final Process process;
        private final Path directory;
        Running(Process process, Path directory) { this.process = process; this.directory = directory; }
        public boolean alive() { return process.isAlive(); }
        @Override public void close() {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            } finally { erase(directory); }
        }
    }
}
