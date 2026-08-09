package com.chat.talkMe.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Public, UNAUTHENTICATED brand assets — served so email clients (which can't send
 * an Authorization header) can load them by URL. Currently, the header logo embedded
 * in transactional emails, served from classpath {@code mail/logo.png}.
 *
 * <p>Must be allow-listed in SecurityConfig ({@code /api/v1/assets/**}).
 */
@RestController
@RequestMapping("/assets") // WebMvcConfig prepends /api/v1 to all @RestControllers → /api/v1/assets
public class PublicAssetController {

    private final Resource logo = new ClassPathResource("mail/logo.png");

    /**
     * Serves the brand logo PNG from the classpath with a 30-day public cache; 404s if the resource is missing.
     *
     * @return the logo image, or 404 Not Found if the classpath resource is absent
     */
    @GetMapping(value = "/logo.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<Resource> logo() {
        if (!logo.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(logo);
    }
}
