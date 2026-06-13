package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.UploadResponse;
import com.chat.talkMe.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final StorageService storageService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ResponseDto<UploadResponse>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type) {
        
        String url = storageService.storeFile(file, type);

        UploadResponse response = UploadResponse.builder()
                .url(url)
                .fileName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .mimeType(file.getContentType())
                .build();

        return ResponseEntity.ok(SuccessResponseDto.success(response, "File uploaded successfully", "TM_167"));
    }

    @GetMapping("/media")
    public ResponseEntity<Resource> getMedia(@RequestParam("path") String path) {
        try {
            Path filePath = Paths.get(path).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                String contentType = Files.probeContentType(filePath);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
