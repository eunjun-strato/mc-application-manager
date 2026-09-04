package kr.co.mcmp.softwarecatalog.application.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStorageListObjectsResponse;
import kr.co.mcmp.ape.cbtumblebug.dto.ObjectStoragePresignedUrlResponse;
import kr.co.mcmp.response.ResponseWrapper;
import kr.co.mcmp.softwarecatalog.application.dto.GrantedObjectStorageDTO;
import kr.co.mcmp.softwarecatalog.application.dto.ObjectStoragePresignedUrlRequest;
import kr.co.mcmp.softwarecatalog.application.service.ObjectStorageGatewayService;
import lombok.RequiredArgsConstructor;

@Hidden
@RestController
@RequestMapping("/applications/object-storage-gateway")
@RequiredArgsConstructor
public class ObjectStorageGatewayController {
    private final ObjectStorageGatewayService gatewayService;

    @GetMapping("/storages")
    public ResponseEntity<ResponseWrapper<List<GrantedObjectStorageDTO>>> listStorages(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok(new ResponseWrapper<>(gatewayService.listStorages(bearerToken(authorization))));
    }

    @GetMapping("/objects")
    public ResponseEntity<ResponseWrapper<ObjectStorageListObjectsResponse>> listObjects(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam String storage,
            @RequestParam(required = false) String prefix) {
        return ResponseEntity.ok(new ResponseWrapper<>(
                gatewayService.listObjects(bearerToken(authorization), storage, prefix)));
    }

    @PostMapping("/presigned-url")
    public ResponseEntity<ResponseWrapper<ObjectStoragePresignedUrlResponse>> createPresignedUrl(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody ObjectStoragePresignedUrlRequest request) {
        return ResponseEntity.ok(new ResponseWrapper<>(
                gatewayService.createPresignedUrl(bearerToken(authorization), request)));
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalArgumentException("Bearer token is required.");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Bearer token is required.");
        }
        return token;
    }
}
