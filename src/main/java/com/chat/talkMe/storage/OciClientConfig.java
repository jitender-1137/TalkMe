package com.chat.talkMe.storage;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Builds the OCI {@link ObjectStorageClient} from API-key credentials — only when
 * {@code storage.provider=oci} (prod). Credentials come from an OCI config file
 * (default {@code ~/.oci/config}) holding the tenancy/user/fingerprint/key-file.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "storage.provider", havingValue = "oci")
public class OciClientConfig {

    @Bean(destroyMethod = "close")
    public ObjectStorageClient objectStorageClient(StorageProperties props) throws IOException {
        StorageProperties.Oci oci = props.getOci();
        String configPath = expandHome(oci.getConfigFile());
        ConfigFileReader.ConfigFile configFile =
                ConfigFileReader.parse(configPath, oci.getConfigProfile());
        AuthenticationDetailsProvider provider =
                new ConfigFileAuthenticationDetailsProvider(configFile);

        ObjectStorageClient client = ObjectStorageClient.builder().build(provider);
        if (oci.getRegion() != null && !oci.getRegion().isBlank()) {
            client.setRegion(Region.fromRegionId(oci.getRegion()));
        }
        if (oci.getNamespace() == null || oci.getNamespace().isBlank()) {
            String ns = client.getNamespace(GetNamespaceRequest.builder().build()).getValue();
            oci.setNamespace(ns);
            log.info("Resolved OCI Object Storage namespace: {}", ns);
        }
        log.info("OCI Object Storage active — region={}, bucket={}", oci.getRegion(), oci.getBucket());
        return client;
    }

    /** Expand a leading {@code ~} to the current user's home directory. */
    private static String expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
