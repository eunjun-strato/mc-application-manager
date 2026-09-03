package kr.co.mcmp.softwarecatalog.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageSmokeTestRequest {
    private String namespace;
    private String clusterName;
    private String mciId;
    private String vmId;
    private Long catalogId;
    private ObjectStorageConfiguration objectStorage;
}
