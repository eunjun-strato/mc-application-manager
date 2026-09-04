package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

/** Opt-in local wire test. Creates ONLY an isolated, loopback-published SSH fixture. */
@EnabledIfEnvironmentVariable(named = "AM_TUNNEL_SSH_SMOKE", matches = "true")
class ObjectStorageTunnelSshSmokeTest {
    String docker = System.getenv().getOrDefault("AM_TEST_DOCKER","docker");

    String command(String... arguments) throws Exception {
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        if (!process.waitFor(45,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Fixture command timeout"); }
        String output = new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage("Fixture command failed: %s",output).isZero();
        return output.strip();
    }

    @Test void forwardsOverRealSshAndRecoversAfterReconnect() throws Exception {
        String id=UUID.randomUUID().toString().replace("-","");
        String name="mcmp-tunnel-test-"+id;
        String socket="/tmp/mcmp-os-"+id+"/gateway.sock";
        Path keys=ObjectStorageTunnelRuntime.privateDirectory();
        HttpServer backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/",e->{
            byte[] payload="{\"code\":200,\"data\":[]}".getBytes();
            e.sendResponseHeaders(200,payload.length);
            e.getResponseBody().write(payload);
            e.close();
        });
        backend.start();
        ObjectStorageTunnelProxy proxy=new ObjectStorageTunnelProxy();
        ReflectionTestUtils.setField(proxy,"backendPort",backend.getAddress().getPort());
        Process ssh=null;
        boolean created=false;
        try {
            command(docker,"run","-d","--name",name,"--label","mcmp.test=tunnel",
                    "-p","127.0.0.1::22","mcmp-am-tunnel-smoke:local");
            created=true;
            command(docker,"exec",name,"ssh-keygen","-q","-t","ed25519","-N","","-f","/tmp/client-key");
            command(docker,"exec",name,"cp","/tmp/client-key.pub","/root/.ssh/authorized_keys");
            command(docker,"exec",name,"chmod","600","/root/.ssh/authorized_keys");
            command(docker,"cp",name+":/tmp/client-key",keys.resolve("key").toString());
            String hostKey=command(docker,"exec",name,"cat","/etc/ssh/ssh_host_ed25519_key.pub");
            Files.writeString(keys.resolve("known_hosts"),"mcmp-"+id+" "+hostKey+"\n");
            Files.writeString(keys.resolve("config"),"");
            command(docker,"exec",name,"mkdir","-m","700","/tmp/mcmp-os-"+id);
            String port=command(docker,"port",name,"22/tcp").split(":")[1].trim();
            String bridge;
            try(var input=new ClassPathResource("tunnel/bridge.py").getInputStream()){
                bridge=new String(input.readAllBytes()).replace("/run/mcmp-tunnel/gateway.sock",socket);
            }
            command(docker,"exec","-d",name,"python3","-c",bridge);
            var arguments=ObjectStorageTunnelRuntime.sshArguments("ssh",id,"127.0.0.1",
                    Integer.parseInt(port),"root",keys,proxy.port());
            for(int pass=0;pass<2;pass++){
                ssh=new ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                String check="import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:18084/healthz',timeout=2).status)";
                boolean ready=false;
                for(int attempt=0;attempt<20 && ssh.isAlive();attempt++){
                    Process probe=new ProcessBuilder(docker,"exec",name,"python3","-c",check)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if(probe.waitFor(4,TimeUnit.SECONDS) && probe.exitValue()==0){ ready=true;break; }
                    probe.destroyForcibly();
                    Thread.sleep(500);
                }
                assertThat(ready).withFailMessage("Real SSH forwarding unavailable (pass %d)",pass).isTrue();
                String allowed="import urllib.request; r=urllib.request.Request('http://127.0.0.1:18084/applications/object-storage-gateway/storages',headers={'Authorization':'Bearer test-only'}); print(urllib.request.urlopen(r,timeout=3).status)";
                assertThat(command(docker,"exec",name,"python3","-c",allowed)).isEqualTo("200");
                String denied="import urllib.request,urllib.error\ntry: urllib.request.urlopen('http://127.0.0.1:18084/applications',timeout=3)\nexcept urllib.error.HTTPError as e: print(e.code)";
                assertThat(command(docker,"exec",name,"python3","-c",denied)).isEqualTo("404");
                ssh.destroyForcibly();
                ssh.waitFor(5,TimeUnit.SECONDS);
                command(docker,"exec",name,"python3","-c",
                        "import pathlib; p=pathlib.Path('"+socket+"'); assert p.is_socket(); p.unlink()");
            }
        } finally {
            if(ssh!=null) ssh.destroyForcibly();
            proxy.close();
            backend.stop(0);
            if(created) command(docker,"rm","-f",name);
            for(String file:List.of("key","known_hosts","config")) Files.deleteIfExists(keys.resolve(file));
            Files.deleteIfExists(keys);
        }
    }
}
