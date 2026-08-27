package kr.co.mcmp.softwarecatalog.docker.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import kr.co.mcmp.softwarecatalog.application.dto.UnifiedLogDTO;
import kr.co.mcmp.softwarecatalog.application.service.UnifiedLogService;
import kr.co.mcmp.softwarecatalog.docker.model.DockerTarget;
import kr.co.mcmp.softwarecatalog.docker.service.DockerLogCollector;
import kr.co.mcmp.softwarecatalog.docker.service.DockerOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerLogCollectorImpl implements DockerLogCollector {

    private final UnifiedLogService unifiedLogService;
    private final DockerOperationService dockerOperationService;

    @Override
    public void collectAndSaveLogs(
            Long deploymentId,
            DockerTarget target,
            String containerId) {
        try {
            List<String> logs = dockerOperationService.getContainerLogs(target, containerId, 100);
            for (String logMessage : logs) {
                UnifiedLogDTO logDTO = UnifiedLogDTO.builder()
                        .deploymentId(deploymentId)
                        .loggedAt(LocalDateTime.now())
                        .severity(resolveSeverity(logMessage))
                        .module(UnifiedLogDTO.LogSourceType.DOCKER.getValue())
                        .logMessage(logMessage)
                        .vmId(target.vmId())
                        .containerName(containerId)
                        .build();
                unifiedLogService.saveLog(logDTO);
            }
            log.info("Saved {} Docker log lines for deployment {}", logs.size(), deploymentId);
        } catch (Exception e) {
            log.error("Failed to collect Docker logs through SSH for deployment {}, VM {}",
                    deploymentId, target.vmId(), e);
        }
    }

    private String resolveSeverity(String message) {
        if (message != null) {
            String normalized = message.toLowerCase();
            if (normalized.contains("error") || normalized.contains("exception")
                    || normalized.contains("fatal")) {
                return UnifiedLogDTO.LogSeverity.ERROR.getValue();
            }
            if (normalized.contains("warn")) {
                return UnifiedLogDTO.LogSeverity.WARN.getValue();
            }
        }
        return UnifiedLogDTO.LogSeverity.INFO.getValue();
    }
}
