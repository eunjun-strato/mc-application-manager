package kr.co.mcmp.softwarecatalog.docker.service;

import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;

/** Collects recent logs from a container through the same SSH control path. */
public interface DockerLogCollector {

    void collectAndSaveLogs(
            Long deploymentId,
            DockerTarget target,
            String containerId);
}
