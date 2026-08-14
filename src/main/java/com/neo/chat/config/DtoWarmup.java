package com.neo.chat.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.stereotype.Component;

/**
 * Eagerly loads every response/request DTO class — and, crucially, their nested
 * classes (Lombok {@code @Builder} generates a nested {@code Foo$FooBuilder}) — at
 * startup.
 *
 * <p>Why: these DTOs are built lazily inside controller/service response mappers, so
 * a nested builder class may not be loaded until the first request that touches it.
 * When the running fat-jar is replaced <em>in place</em> during a deployment (overwriting
 * the open file rather than an atomic rename + restart), the JVM's view of the jar's
 * central directory is invalidated and any <em>not-yet-loaded</em> class fails with
 * {@code NoClassDefFoundError / ClassNotFoundException} (e.g. {@code AdminPostView$Media$MediaBuilder}
 * on the admin News endpoint). Once a class is loaded it stays resident, so loading
 * them all up-front closes that window.
 *
 * <p>The real fix is an atomic deployment (rename/new path + restart, never overwrite the
 * running jar); this is defense-in-depth, mirroring {@link LoggingWarmup}. Best-effort
 * only — it never fails startup.
 */
@Slf4j
@Component
public class DtoWarmup {

    private static final String[] PACKAGES = {
            "com.neo.chat.dto.response",
            "com.neo.chat.dto.request",
    };

    /**
     * On startup, scans the request/response DTO packages and eagerly loads every class
     * (and recursively its nested builder classes) so none can later fail to load after an
     * in-place jar swap. Best-effort: scanning or a single class failing never aborts startup.
     */
    @PostConstruct
    void warmUp() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        // useDefaultFilters=false + an accept-all include filter => every class in the package.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((_, _) -> true);

        int loaded = 0;
        for (String pkg : PACKAGES) {
            try {
                for (var bd : scanner.findCandidateComponents(pkg)) {
                    String name = bd.getBeanClassName();
                    if (name == null) continue;
                    try {
                        loaded += load(name, cl);
                    } catch (Throwable ignored) {
                        // A single class that won't load must never abort the warmup.
                    }
                }
            } catch (Throwable ignored) {
                // Scanning itself must never abort startup.
            }
        }
        log.info("[DtoWarmup] eager-loaded {} DTO classes (incl. nested builders)", loaded);
    }

    /**
     * Load a class and recursively force-load its nested classes (Lombok builders, etc.).
     *
     * @param className fully-qualified class name to load.
     * @param cl        the class loader to load through.
     * @return the count of classes loaded (this class plus all nested classes).
     * @throws java.lang.ClassNotFoundException if the named class itself cannot be found.
     */
    private int load(String className, ClassLoader cl) throws ClassNotFoundException {
        Class<?> c = Class.forName(className, true, cl);
        int n = 1;
        for (Class<?> nested : c.getDeclaredClasses()) {
            try {
                n += load(nested.getName(), cl);
            } catch (Throwable ignored) {
                // Skip a problematic nested class without failing the parent.
            }
        }
        return n;
    }
}
