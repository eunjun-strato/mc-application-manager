package kr.co.mcmp.softwarecatalog.docker.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.MciCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerCommandResult;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes fixed, server-generated shell scripts through CB-Tumblebug SSH.
 *
 * Scripts are base64 encoded before transport so nested quotes, environment
 * values and Docker Go templates cannot alter the outer SSH command. Every
 * script runs in a subshell and emits a machine-readable exit marker.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DockerSshCommandExecutor {

    static final String EXIT_MARKER = "__MCMP_EXIT_CODE__=";
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile(
            "(?:^|\\n)" + EXIT_MARKER + "(-?\\d+)\\s*$");
    private static final int ERROR_DETAIL_LIMIT = 2_000;

    private final CbtumblebugRestApi cbtumblebugRestApi;

    public DockerCommandResult execute(DockerTarget target, String script) {
        DockerCommandResult result = executeAllowFailure(target, script);
        if (!result.successful()) {
            String detail = !result.stderr().isBlank() ? result.stderr() : result.stdout();
            throw new DockerCommandException(
                    target,
                    result.exitCode(),
                    "Remote Docker command failed on VM " + target.vmId()
                            + " (exit=" + result.exitCode() + "): " + abbreviate(detail));
        }
        return result;
    }

    public DockerCommandResult executeAllowFailure(DockerTarget target, String script) {
        if (target == null) {
            throw new IllegalArgumentException("Docker target is required");
        }
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("Docker command script is required");
        }

        String wrappedScript = "(\n" + script + "\n)\n"
                + "mcmp_status=$?\n"
                + "printf '\\n" + EXIT_MARKER + "%s\\n' \"$mcmp_status\"\n"
                + "exit \"$mcmp_status\"\n";
        String encoded = Base64.getEncoder().encodeToString(wrappedScript.getBytes(StandardCharsets.UTF_8));
        String transportCommand = "printf '%s' " + shellQuote(encoded) + " | base64 -d | sh";

        MciCommandResult remoteResult = cbtumblebugRestApi.executeMciCommandResult(
                target.namespace(), target.mciId(), transportCommand, null, target.vmId());
        Matcher marker = EXIT_CODE_PATTERN.matcher(remoteResult.stdout());
        if (!marker.find()) {
            throw new DockerCommandException(
                    target,
                    -1,
                    "Remote Docker command on VM " + target.vmId()
                            + " returned no completion marker: " + abbreviate(remoteResult.stderr()));
        }

        int exitCode;
        try {
            exitCode = Integer.parseInt(marker.group(1));
        } catch (NumberFormatException e) {
            throw new DockerCommandException(target, -1, "Invalid remote command exit marker");
        }

        String stdout = remoteResult.stdout().substring(0, marker.start()).stripTrailing();
        log.debug("Remote Docker command completed on VM {} with exit code {}", target.vmId(), exitCode);
        return new DockerCommandResult(exitCode, stdout, remoteResult.stderr().stripTrailing());
    }

    /** POSIX-safe quoting for one shell argument. */
    public static String shellQuote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Shell argument must not be null");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "no error output";
        }
        String normalized = value.strip();
        return normalized.length() <= ERROR_DETAIL_LIMIT
                ? normalized
                : normalized.substring(0, ERROR_DETAIL_LIMIT) + "...";
    }
}
