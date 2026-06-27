package com.chat.talkMe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
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
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        if (resourcePath.isEmpty()) {
                            return readable(location.createRelative("index.html"));
                        }

                        boolean hasExtension = lastSegmentHasExtension(resourcePath);

                        // 1) Extensionless route -> serve "<path>.html" if present.
                        if (!hasExtension) {
                            Resource html = readable(location.createRelative(resourcePath + ".html"));
                            if (html != null) {
                                return html;
                            }
                        }

                        // 2) Exact file (JS/CSS/images, sw.js, manifest.json, *.html, …).
                        Resource exact = readable(location.createRelative(resourcePath));
                        if (exact != null) {
                            return exact;
                        }

                        // 3) Unknown extensionless path -> SPA fallback to index.html.
                        //    (A missing file *with* an extension falls through to a
                        //    normal 404 so assets don't masquerade as HTML.)
                        if (!hasExtension) {
                            return readable(location.createRelative("index.html"));
                        }
                        return null;
                    }
                });
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
