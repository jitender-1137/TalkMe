package com.neo.chat.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Media-storage configuration. {@link #mediaRoot} is the single root shared by both
 * backends (OCI object keys and the local/instance disk) — the stored reference is
 * always {@code <mediaRoot>/<key>}. {@link Oci} holds the OCI-specific settings, used
 * only when {@code storage.provider=oci}.
 *
 * <p>Immutable at the top level: bound once at startup through constructor binding (registered
 * by {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 * The nested {@link Oci} block stays a JavaBean so {@code storage.oci.*} binds as before.
 */
@Getter
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * Root for all media references/keys (OCI + instance disk). Defaults to /media.
     */
    private final String mediaRoot;

    /**
     * PREVIOUS media-roots to also recognise when resolving a stored reference to an object key.
     *
     * <p>Object keys never contain the media-root (see {@code MediaStorage.store}), so the same
     * bytes live under the same key no matter which root was current when a DB row was written.
     * After the root is renamed (e.g. {@code /opt/media/talkMe} → {@code /opt/media/neochathub}),
     * old references still carry the old root and would otherwise resolve to a wrong key and 404.
     * Listing an old root here lets {@code MediaKeys.key} strip it too, so old media keeps loading.
     * Bound from {@code storage.legacy-media-roots} (comma-separated).
     */
    private final List<String> legacyMediaRoots;

    private final Oci oci;

    /**
     * Defaults: {@code /media} root, no legacy roots, default OCI block.
     */
    public StorageProperties() {
        this("/media", new ArrayList<>(), new Oci());
    }

    /**
     * Binds {@code storage.*}.
     *
     * @param mediaRoot        shared media root (default {@code /media})
     * @param legacyMediaRoots previous roots still carried by old references; {@code null}
     *                         (unset) yields an empty list
     * @param oci              the {@code storage.oci.*} block; {@code null} (nothing configured)
     *                         yields the default block so callers never see a null
     */
    @ConstructorBinding
    public StorageProperties(@DefaultValue("/media") String mediaRoot,
                             List<String> legacyMediaRoots,
                             Oci oci) {
        this.mediaRoot = mediaRoot;
        this.legacyMediaRoots = legacyMediaRoots != null ? legacyMediaRoots : new ArrayList<>();
        this.oci = oci != null ? oci : new Oci();
    }

    @Getter
    @Setter
    public static class Oci {
        /**
         * Object Storage namespace (tenancy namespace). Auto-resolved at startup if blank.
         */
        private String namespace;
        /**
         * OCI region identifier, e.g. "ap-mumbai-1".
         */
        private String region;
        /**
         * The single bucket all media is stored in (shared across backend instances).
         */
        private String bucket = "neochathub-media";
        /**
         * Path to the OCI API-key config file (supports a leading ~).
         */
        private String configFile = "~/.oci/config";
        /**
         * Profile section within the config file.
         */
        private String configProfile = "DEFAULT";
    }
}
