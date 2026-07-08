package com.prodigalgal.ircs.storage.image;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@Slf4j
public class R2ObjectStorage {

    private final boolean enabled;
    private final String bucketName;
    private final String accountId;
    private final String accessKey;
    private final String secretKey;
    private final String endpoint;

    public R2ObjectStorage(
            @Value("${app.storage.r2.enabled:false}") boolean enabled,
            @Value("${app.storage.r2.bucket-name:}") String bucketName,
            @Value("${app.storage.r2.account-id:}") String accountId,
            @Value("${app.storage.r2.access-key:}") String accessKey,
            @Value("${app.storage.r2.secret-key:}") String secretKey,
            @Value("${app.storage.r2.endpoint:}") String endpoint) {
        this.enabled = enabled;
        this.bucketName = bucketName;
        this.accountId = accountId;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
    }

    public boolean isActive() {
        return enabled
                && StringUtils.hasText(bucketName)
                && StringUtils.hasText(accessKey)
                && StringUtils.hasText(secretKey)
                && (StringUtils.hasText(endpoint) || StringUtils.hasText(accountId));
    }

    public void delete(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            return;
        }
        if (!isActive()) {
            log.info("R2 delete skipped because R2 storage is disabled or incomplete");
            return;
        }
        try (S3Client client = buildClient()) {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(extractKey(storagePath))
                    .build());
            log.info("Deleted R2 storage object: {}", storagePath);
        }
    }

    public boolean exists(String storagePath) {
        if (!StringUtils.hasText(storagePath) || !isActive()) {
            return false;
        }
        try (S3Client client = buildClient()) {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(extractKey(storagePath))
                    .build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        }
    }

    public void store(byte[] data, String storagePath, String contentType) {
        if (!isActive()) {
            throw new IllegalStateException("R2 storage is disabled or incomplete");
        }
        try (S3Client client = buildClient()) {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(extractKey(storagePath))
                            .contentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(data));
            log.info("Stored R2 storage object: {}", storagePath);
        }
    }

    private S3Client buildClient() {
        String targetEndpoint = StringUtils.hasText(endpoint)
                ? endpoint
                : "https://" + accountId + ".r2.cloudflarestorage.com";
        return S3Client.builder()
                .endpointOverride(URI.create(targetEndpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    private String extractKey(String storagePath) {
        if (storagePath.startsWith("http")) {
            int coversIndex = storagePath.lastIndexOf("/covers/");
            if (coversIndex >= 0) {
                return storagePath.substring(coversIndex + 1);
            }
            return storagePath.substring(storagePath.lastIndexOf('/') + 1);
        }
        return storagePath;
    }
}
