package kr.co.mcmp.ape.cbtumblebug.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStoragePresignedUrlResponse {
    private Long expires;
    private String method;

    @JsonProperty("presignedURL")
    private String presignedUrl;

    @Builder.Default
    private Map<String, String> requiredHeaders = new LinkedHashMap<>();
}
