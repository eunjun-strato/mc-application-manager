package kr.co.mcmp.softwarecatalog.application.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageConfiguration {
    private Boolean enabled;

    @Builder.Default
    private List<ObjectStorageSelection> storages = new ArrayList<>();

    private String jupyterToken;

    // Legacy S3-compatible settings are retained for existing K8s catalog flows.
    private String backendType;
    private String endpoint;
    private String region;
    private String bucket;
    private String accessKey;
    private String secretKey;
    private String sessionToken;
    private String prefix;
    private Boolean forcePathStyle;
    private Boolean insecure;
}
