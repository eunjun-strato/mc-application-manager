package kr.co.mcmp.softwarecatalog.docker.model;

/**
 * Structured result produced by a Docker command executed over SSH.
 */
public record DockerCommandResult(int exitCode, String stdout, String stderr) {

    public DockerCommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public boolean successful() {
        return exitCode == 0;
    }
}
