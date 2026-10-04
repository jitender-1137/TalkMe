package com.neo.chat.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory catalog of the preset ("cute") avatars, loaded ONCE at startup from the
 * build-generated {@code avatars-manifest.json} (produced by the frontend's
 * {@code pnpm gen:avatars}; the generator writes the same source of truth to both the
 * frontend and this classpath resource so they never drift).
 *
 * <p>Used to validate a preset avatar id server-side and resolve it to its stable
 * public path, without any per-request database access.
 */
@Slf4j
@Component
public class AvatarCatalog {

    private final ObjectMapper objectMapper;
    /** avatar id -> stored path, e.g. "female-01" -> "/avatars/female/female-01.webp". */
    private Map<String, String> idToPath = Collections.emptyMap();

    public AvatarCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("avatars-manifest.json").getInputStream()) {
            Manifest manifest = objectMapper.readValue(in, Manifest.class);
            Map<String, String> map = new HashMap<>();
            if (manifest != null && manifest.avatars != null) {
                for (Entry e : manifest.avatars) {
                    if (e.id != null && e.path != null) {
                        map.put(e.id, e.path);
                    }
                }
            }
            this.idToPath = Collections.unmodifiableMap(map);
            log.info("AvatarCatalog loaded {} preset avatars", idToPath.size());
        } catch (Exception ex) {
            // Non-fatal: the picker simply has nothing to validate against (empty set).
            log.warn("AvatarCatalog: could not load avatars-manifest.json; preset avatars disabled", ex);
        }
    }

    /** True if the id is a known preset avatar. */
    public boolean isValidId(String id) {
        return id != null && idToPath.containsKey(id);
    }

    /** Resolve a preset id to its stored public path, or {@code null} if unknown. */
    public String resolvePath(String id) {
        return id == null ? null : idToPath.get(id);
    }

    /** True if the given stored value is a known preset path (for write-time validation). */
    public boolean isValidPath(String path) {
        return path != null && idToPath.containsValue(path);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Manifest {
        public List<Entry> avatars;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Entry {
        public String id;
        public String collection;
        public String path;
    }
}
