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
public class ObjectStorageListObjectsResponse {
    @Builder.Default
    private List<ObjectStorageObject> objects = new ArrayList<>();
}
