package com.neo.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Spring MVC web configuration. Prefixes every {@code @RestController} mapping with
 * {@code /api/v1} (see {@link #configurePathMatch}) and serves the bundled Next.js static export with an in-memory
 * caching resolver that maps clean URLs to {@code .html} files, falls back to
 * {@code index.html} for SPA routes, and avoids concurrent fat-jar inflation corruption.
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * Legacy Jackson 2 {@link ObjectMapper} used by services that persist/parse JSON blobs
     * (Redis buffers, stored payloads, external API clients — ~25 injection points).
     * <p>
     * It is deliberately {@link Fallback}, not {@code @Primary}: Spring Boot 4 auto-configures the
     * Jackson 3 {@code jacksonJsonMapper} (customised in {@code JacksonConfig}) as the application's
     * JSON mapper for HTTP (de)serialization, and that is the one to prefer going forward. Because
     * this is the only bean of the Jackson 2 type, it still satisfies every Jackson 2 injection
     * point; the annotation records intent and keeps the two mappers unambiguous (BootUI Spring
     * Advisor SPRING-WIRING-003). Remove once callers migrate to {@code tools.jackson}.
     *
     * @return a default Jackson 2 {@link ObjectMapper}.
     */
    @Bean
    @Fallback
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Registers the static resource handlers: {@code /_next/static/**} (immutable, long-cached)
     * and {@code /**} → {@code classpath:/static/}
     * served through {@link CachingSpaResourceResolver} for clean-URL/SPA routing plus
     * in-memory caching. API routes are unaffected (handled by controllers under {@code /api/v1}).
     *
     * @param registry the {@link ResourceHandlerRegistry} to configure.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // SECURITY: the former "/talkMe/**" -> file:<upload dir> handler is GONE. It served the
        // whole media root (private chat media, avatars, view-once files) to anonymous callers
        // and rendered uploaded HTML/SVG inline on this origin. All media goes through
        // UploadController.getMedia, which authorizes per object. SecurityConfig also denies
        // /talkMe/** so it can never be reintroduced by accident.

        // ── Next.js immutable build assets (/_next/static/**) ───────────────────
        // Everything the Next.js export emits under /_next/static/ is CONTENT-HASHED
        // (the chunk name changes whenever its bytes change), so a given URL can
        // never serve different content. Mark it cacheable for a year and
        // `immutable` so browsers (and any CDN/proxy) serve it straight from disk on
        // every repeat visit — ZERO revalidation round-trips. This is the single
        // biggest lever on cold-open latency: without it, each of the ~30 hashed
        // chunks was re-validated over the network on every open.
        // Registered BEFORE "/**" so the long-lived caching wins for these paths.
        registry.addResourceHandler("/_next/static/**")
                .addResourceLocations("classpath:/static/_next/static/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .resourceChain(true)
                .addResolver(new CachingSpaResourceResolver());

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
        //
        // Cache-Control: `no-cache` = the browser MAY store the file but must
        // revalidate with the server before reuse. This is exactly right for the
        // HTML shell (index.html / *.html) and other un-hashed root assets: a new
        // deploy is picked up immediately (the revalidation is a cheap 304), while
        // the heavy hashed chunks above still load from cache with no network at
        // all. The service worker adds true offline fallback on top of this.
        // ── Preset ("cute") avatar assets (/avatars/**) ─────────────────────────
        // Build-generated avatars (public/avatars → bundled into the export). Names
        // are logical + deterministic (the generator reproduces identical art for a
        // given id from a fixed seed), so a given URL's bytes never change → cache a
        // year, immutable, like the hashed _next chunks (ZERO revalidation on repeat
        // visits). Registered BEFORE "/**" so this long-lived caching wins. The
        // caching resolver also serves them from the in-memory inflate cache,
        // avoiding the nested-jar ZipException under concurrency.
        registry.addResourceHandler("/avatars/**")
                .addResourceLocations("classpath:/static/avatars/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .resourceChain(true)
                .addResolver(new CachingSpaResourceResolver());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new CachingSpaResourceResolver());
    }

    /**
     * Resolves SPA/static routes to bundled resources and serves their bytes from an
     * in-memory cache (inflated out of the fat-jar at most once, under a lock). See
     * {@link #addResourceHandlers} for why the cache is required.
     */
    private static final class CachingSpaResourceResolver extends PathResourceResolver {

        /**
         * key (resource URL) -> fully-read, in-memory copy. Bounded by the static bundle size.
         */
        private final ConcurrentHashMap<String, Resource> cache = new ConcurrentHashMap<>();
        /**
         * Serializes the (rare) cold reads so two threads never inflate from the jar at once.
         */
        private final Object inflateLock = new Object();

        /**
         * Resolves a request path to a cached resource: an extensionless route prefers
         * {@code <path>.html}, then an exact file match, then (for extensionless paths only)
         * falls back to {@code index.html} for SPA routing. A missing path that has an
         * extension returns {@code null} so it 404s normally.
         *
         * @param resourcePath the request path relative to the handler mapping.
         * @param location     the configured static resource location.
         * @return the resolved (cached) {@link Resource}, or {@code null} if none matches.
         * @throws java.io.IOException if reading the underlying resource fails.
         */
        @Override
        protected Resource getResource(String resourcePath, @NonNull Resource location) throws IOException {
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

        /**
         * Return an in-memory copy of {@code original}, reading it from the jar at most once.
         */
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

    /**
     * Returns the resource only if it exists and is readable, else {@code null}.
     *
     * @param resource the candidate resource.
     * @return the resource when usable, otherwise {@code null}.
     */
    private static Resource readable(Resource resource) {
        return (resource.exists() && resource.isReadable()) ? resource : null;
    }

    /**
     * True if the last path segment contains a dot (i.e. looks like a file, not a route).
     */
    private static boolean lastSegmentHasExtension(String path) {
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
        return lastSegment.contains(".");
    }

    /**
     * Prefixes all {@code @RestController}-annotated handler mappings with {@code /api/v1}.
     *
     * @param configurer the {@link PathMatchConfigurer} to apply the prefix to.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Prefix ONLY this app's own @RestControllers. Scoping to the base package is essential:
        // an unscoped forAnnotation(RestController.class) also rewrites THIRD-PARTY starters'
        // controllers (BootUI at /bootui/api/**, springdoc at /v3/api-docs), shifting them under
        // /api/v1/... so their own frontends 404. Restricting to com.neo.chat keeps every app API
        // under /api/v1 while leaving those libraries at their native paths.
        configurer.addPathPrefix("/api/v1",
                HandlerTypePredicate.forAnnotation(RestController.class)
                        .and(HandlerTypePredicate.forBasePackage("com.neo.chat")));
    }
}
