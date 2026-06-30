package com.chat.talkMe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${storage.local.directory}")
    private String uploadDir;

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // User-uploaded files. More specific pattern than "/**" below, so it is
        // matched first.
        registry.addResourceHandler("/talkMe/**")
                .addResourceLocations("file:" + uploadDir + "/");

        // Bundled Next.js static export. The export emits "clean URL" pages as
        // <route>.html (e.g. /blog -> blog.html, /blog/<slug> -> blog/<slug>.html,
        // /welcome -> welcome.html, /app -> app.html). Spring's default handler
        // serves exact paths only, so an extensionless request like "/blog" 404s
        // (NoResourceFoundException -> TM_004). This resolver maps an extensionless
        // request to its ".html" file, and falls back to index.html so the SPA's
        // client-side (hash) routes still load. API routes are unaffected — they
        // are handled by @RestController mappings under /api/v1 before this runs.
        //
        // The resolver also serves each asset from an in-memory byte cache: the
        // static bundle lives inside the executable fat-jar as DEFLATE-compressed
        // entries, and inflating the same entry concurrently out of the nested jar
        // intermittently corrupts the stream (java.util.zip.ZipException: "invalid
        // distance too far back"). Caching means each entry is inflated at most once
        // (and inflation is serialized), so concurrent requests are served from
        // memory and never re-enter the jar inflater.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new CachingSpaResourceResolver());
    }

    /**
     * Resolves SPA/static routes to bundled resources and serves their bytes from an
     * in-memory cache (inflated out of the fat-jar at most once, under a lock). See
     * {@link #addResourceHandlers} for why the cache is required.
     */
    private static final class CachingSpaResourceResolver extends PathResourceResolver {

        /** key (resource URL) -> fully-read, in-memory copy. Bounded by the static bundle size. */
        private final ConcurrentHashMap<String, Resource> cache = new ConcurrentHashMap<>();
        /** Serializes the (rare) cold reads so two threads never inflate from the jar at once. */
        private final Object inflateLock = new Object();

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (resourcePath.isEmpty()) {
                return cached(readable(location.createRelative("index.html")));
            }

            boolean hasExtension = lastSegmentHasExtension(resourcePath);

            // 1) Extensionless route -> serve "<path>.html" if present.
            if (!hasExtension) {
                Resource html = cached(readable(location.createRelative(resourcePath + ".html")));
                if (html != null) {
                    return html;
                }
            }

            // 2) Exact file (JS/CSS/images, sw.js, manifest.json, *.html, …).
            Resource exact = cached(readable(location.createRelative(resourcePath)));
            if (exact != null) {
                return exact;
            }

            // 3) Unknown extensionless path -> SPA fallback to index.html.
            //    (A missing file *with* an extension falls through to a normal 404 so
            //    assets don't masquerade as HTML.)
            if (!hasExtension) {
                return cached(readable(location.createRelative("index.html")));
            }
            return null;
        }

        /** Return an in-memory copy of {@code original}, reading it from the jar at most once. */
        private Resource cached(Resource original) throws IOException {
            if (original == null) {
                return null;
            }
            String key;
            try {
                key = original.getURL().toString();
            } catch (IOException e) {
                key = original.getDescription();
            }
            Resource hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
            synchronized (inflateLock) {
                hit = cache.get(key);
                if (hit != null) {
                    return hit;
                }
                byte[] data;
                try (InputStream in = original.getInputStream()) {
                    data = in.readAllBytes();
                }
                long lastModified;
                try {
                    lastModified = original.lastModified();
                } catch (IOException e) {
                    lastModified = 0L;
                }
                Resource mem = new CachedResource(data, original.getFilename(), lastModified);
                cache.put(key, mem);
                return mem;
            }
        }
    }

    /**
     * An in-memory resource that preserves the original filename (for content-type
     * detection) and last-modified time (for caching/conditional requests), and never
     * throws from {@link #lastModified()} the way a bare {@link ByteArrayResource} would.
     */
    private static final class CachedResource extends ByteArrayResource {
        private final String filename;
        private final long lastModified;

        CachedResource(byte[] data, String filename, long lastModified) {
            super(data);
            this.filename = filename;
            this.lastModified = lastModified;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long lastModified() {
            return lastModified; // 0 when unknown — Spring treats that as "no Last-Modified"
        }
    }

    private static Resource readable(Resource resource) throws IOException {
        return (resource.exists() && resource.isReadable()) ? resource : null;
    }

    /** True if the last path segment contains a dot (i.e. looks like a file, not a route). */
    private static boolean lastSegmentHasExtension(String path) {
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
        return lastSegment.contains(".");
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1", HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
