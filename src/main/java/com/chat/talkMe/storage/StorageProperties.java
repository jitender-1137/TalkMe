package com.chat.talkMe.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Media-storage configuration. {@link #mediaRoot} is the single root shared by both
 * backends (OCI object keys and the local/instance disk) — the stored reference is
 * always {@code <mediaRoot>/<key>}. {@link Oci} holds the OCI-specific settings, used
 * only when {@code storage.provider=oci}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /** Root for all media references/keys (OCI + instance disk). Defaults to /media. */
    private String mediaRoot = "/media";

    private final Oci oci = new Oci();

    @Getter
    @Setter
    public static class Oci {
        /** Object Storage namespace (tenancy namespace). Auto-resolved at startup if blank. */
        private String namespace;
        /** OCI region identifier, e.g. "ap-mumbai-1". */
        private String region;
        /** The single bucket all media is stored in (shared across backend instances). */
        private String bucket = "neochathub-media";
        /** Path to the OCI API-key config file (supports a leading ~). */
        private String configFile = "~/.oci/config";
        /** Profile section within the config file. */
        private String configProfile = "DEFAULT";
    }
}
