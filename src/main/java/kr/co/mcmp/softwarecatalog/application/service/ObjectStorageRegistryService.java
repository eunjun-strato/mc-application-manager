package kr.co.mcmp.softwarecatalog.application.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import kr.co.mcmp.ape.cbtumblebug.api.CbtumblebugRestApi;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStorageInfo;
import kr.co.mcmp.softwarecatalog.application.dto.RegisteredObjectStorageDTO;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ObjectStorageRegistryService {
    private final CbtumblebugRestApi cbtumblebugRestApi;

    public List<RegisteredObjectStorageDTO> list(String namespace) {
        return cbtumblebugRestApi.getObjectStorages(namespace).stream()
                .map(this::toDto)
                .sorted(Comparator.comparing(RegisteredObjectStorageDTO::getId, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private RegisteredObjectStorageDTO toDto(ObjectStorageInfo info) {
        ObjectStorageInfo.ConnectionConfig connection = info.getConnectionConfig();
        ObjectStorageInfo.RegionZoneInfo regionZone = connection == null ? null : connection.getRegionZoneInfo();
        return RegisteredObjectStorageDTO.builder()
                .id(info.getId())
                .name(info.getName())
                .status(info.getStatus())
                .provider(connection == null ? "" : connection.getProviderName())
                .region(regionZone == null ? "" : regionZone.getAssignedRegion())
                .connectionName(info.getConnectionName())
                .build();
    }
}
