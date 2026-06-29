package com.chat.talkMe.service;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    /** Store under the media root (no subfolder). Back-compat for uncategorized uploads. */
    String storeFile(MultipartFile file, String type);

    /**
     * Store under {@code <mediaRoot>/<subdir>/<random>.<ext>}. The subdir is built
     * from server-trusted pieces (a fixed category + a validated UUID) by the caller;
     * this method still canonicalizes it and refuses anything that escapes the media
     * root. Pass an empty/blank subdir to store at the root.
     */
    String storeFile(MultipartFile file, String type, String subdir);
}
