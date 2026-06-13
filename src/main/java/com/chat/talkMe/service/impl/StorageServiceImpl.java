package com.chat.talkMe.service.impl;

import com.chat.talkMe.exception.FileStorageException;
import com.chat.talkMe.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Slf4j
@Service
public class StorageServiceImpl implements StorageService {

    private static final Path STORAGE_PATH = Paths.get("/opt/media/talkMe");

    public StorageServiceImpl() {
        try {
            Files.createDirectories(STORAGE_PATH);
        } catch (IOException e) {
            throw new FileStorageException(
                    "Could not create storage directory: " + STORAGE_PATH + e);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String type) {

        String originalFileName = file.getOriginalFilename();
        String extension = "";

        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }

        String fileName = UUID.randomUUID() + extension;

        try {
            Path targetLocation = STORAGE_PATH.resolve(fileName);

            Files.copy(
                    file.getInputStream(),
                    targetLocation,
                    StandardCopyOption.REPLACE_EXISTING
            );

            log.info("File stored successfully: {}", targetLocation);

            return targetLocation.toString();

        } catch (IOException e) {
            throw new FileStorageException(
                    "Could not store file " + fileName + e);
        }
    }
}