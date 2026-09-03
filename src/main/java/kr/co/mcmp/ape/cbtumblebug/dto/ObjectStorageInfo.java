package kr.co.mcmp.ape.cbtumblebug.dto;

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
public class ObjectStorageInfo {
    private String resourceType;
    private String id;
    private String uid;
    private String cspResourceName;
    private String cspResourceId;
    private String connectionName;
    private ConnectionConfig connectionConfig;
    private String description;
    private String status;
    private String name;
    private String creationDate;

    @Builder.Default
    private List<ObjectStorageObject> contents = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConnectionConfig {
        private String configName;
        private String providerName;
        private String credentialName;
        private String credentialHolder;
        private RegionZoneInfo regionZoneInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegionZoneInfo {
        private String assignedRegion;
        private String assignedZone;
    }
}
