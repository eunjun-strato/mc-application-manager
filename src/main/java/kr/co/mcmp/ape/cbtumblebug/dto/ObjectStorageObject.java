package kr.co.mcmp.ape.cbtumblebug.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageObject {
    private String key;
    private String lastModified;
    private String eTag;
    private Long size;
    private String storageClass;
}
