package kr.co.mcmp.softwarecatalog.application.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrantedObjectStorageDTO {
    private String alias;
    private String objectStorageId;
    private String provider;
    private String prefix;
    private String accessMode;
    private LocalDateTime expiresAt;
}
