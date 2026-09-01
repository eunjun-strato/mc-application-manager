package kr.co.mcmp.softwarecatalog.docker.service;

import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;

/**
 * Raised when a command reached the VM but failed there, or when its structured
 * completion marker was not returned.
 */
public class DockerCommandException extends RuntimeException {

    private final DockerTarget target;
    private final int exitCode;

    public DockerCommandException(DockerTarget target, int exitCode, String message) {
        super(message);
        this.target = target;
        this.exitCode = exitCode;
    }

    public DockerTarget getTarget() {
        return target;
    }

    public int getExitCode() {
        return exitCode;
    }
}
