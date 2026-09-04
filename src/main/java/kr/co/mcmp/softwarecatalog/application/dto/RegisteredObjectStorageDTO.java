package kr.co.mcmp.softwarecatalog.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisteredObjectStorageDTO {
    private String id;
    private String name;
    private String status;
    private String provider;
    private String region;
    private String connectionName;
}
