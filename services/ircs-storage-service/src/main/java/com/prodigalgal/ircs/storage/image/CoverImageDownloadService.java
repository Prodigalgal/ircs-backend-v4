package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.storage.StorageWorkPublisher;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CoverImageDownloadService {

    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/avif",
            "image/bmp",
            "image/x-icon");

    private final CoverImageAdminRepository repository;
    private final CoverImageUrlResolver urlResolver;
    private final FileNormalizationService normalizationService;
    private final LocalObjectStorage localObjectStorage;
    private final R2ObjectStorage r2ObjectStorage;
    private final CoverImageTrafficLimiter trafficLimiter;
    private final StorageWorkPublisher storageWorkPublisher;
    private OutboundUrlPolicy outboundUrlPolicy;
    private CoverImageDownloadAdapter downloadAdapter;

    @Value("${app.storage.cover-path-prefix:covers}")
    private String coverPathPrefix;

    @Value("${app.storage.image.download.enabled:true}")
    private boolean downloadEnabled;

    @Value("${app.storage.image.allow-local-addresses:false}")
    private boolean allowLocalAddresses;

    @Value("${app.storage.image.max-retries:3}")
    private int maxRetries;

    @Value("${app.storage.image.download-timeout-seconds:15}")
    private int downloadTimeoutSeconds;
    public CoverImageDownloadService(
            CoverImageAdminRepository repository,
            CoverImageUrlResolver urlResolver,
            FileNormalizationService normalizationService,
            LocalObjectStorage localObjectStorage,
            R2ObjectStorage r2ObjectStorage,
            StorageWorkPublisher storageWorkPublisher,
            ObjectProvider<CoverImageTrafficLimiter> trafficLimiterProvider) {
        this.repository = repository;
        this.urlResolver = urlResolver;
        this.normalizationService = normalizationService;
        this.localObjectStorage = localObjectStorage;
        this.r2ObjectStorage = r2ObjectStorage;
        this.storageWorkPublisher = storageWorkPublisher;
        CoverImageTrafficLimiter providedTrafficLimiter = trafficLimiterProvider == null
                ? null
                : trafficLimiterProvider.getIfAvailable(CoverImageTrafficLimiter::noop);
        this.trafficLimiter = providedTrafficLimiter == null ? CoverImageTrafficLimiter.noop() : providedTrafficLimiter;
        this.outboundUrlPolicy = new OutboundUrlPolicy(new DefaultOutboundAddressResolver());
    }

    static CoverImageDownloadService forTest(
            CoverImageAdminRepository repository,
            CoverImageUrlResolver urlResolver,
            FileNormalizationService normalizationService,
            LocalObjectStorage localObjectStorage,
            R2ObjectStorage r2ObjectStorage,
            StorageWorkPublisher storageWorkPublisher,
            OutboundUrlPolicy outboundUrlPolicy,
            CoverImageDownloadAdapter downloadAdapter) {
        CoverImageDownloadService service = new CoverImageDownloadService(
                repository,
                urlResolver,
                normalizationService,
                localObjectStorage,
                r2ObjectStorage,
                storageWorkPublisher,
                null);
        service.outboundUrlPolicy = outboundUrlPolicy;
        service.downloadAdapter = downloadAdapter;
        return service;
    }

    public void process(UUID imageId) {
        if (!downloadEnabled) {
            log.info("Cover image download is disabled, skipping {}", imageId);
            return;
        }
        CoverImageRow row = repository.findRowById(imageId).orElse(null);
        if (row == null) {
            log.warn("Cover image {} not found during download", imageId);
            return;
        }
        if (!repository.markFetching(imageId)) {
            log.info("Cover image {} is not eligible for download", imageId);
            return;
        }
        try {
            String remoteUrl = urlResolver.resolve(row);
            if (!StringUtils.hasText(remoteUrl)) {
                throw new IllegalStateException("Cover image URL is not downloadable");
            }
            remoteUrl = remoteUrl.trim();
            URI remoteUri = validateDownloadUri(remoteUrl);
            trafficLimiter.acquire(remoteUri);
            DownloadedImage image = download(remoteUri);
            NormalizedFile file = normalizationService.normalize(image.data(), image.contentType(), coverPathPrefix);
            if (!localObjectStorage.exists(file.storageKey())) {
                localObjectStorage.store(file.data(), file.storageKey(), file.mimeType());
            }
            boolean r2Active = r2ObjectStorage.isActive();
            repository.finalizeDownload(imageId, file);
            if (r2Active) {
                storageWorkPublisher.enqueueCoverR2Sync(imageId, "cover-download");
            }
            log.info("Downloaded cover image: id={}, path={}", imageId, file.storageKey());
        } catch (Exception ex) {
            repository.markFailed(imageId, ex.getMessage(), maxRetries);
            log.warn("Cover image download failed: id={}, reason={}", imageId, ex.getMessage());
        }
    }

    private DownloadedImage download(URI remoteUri) throws Exception {
        CoverImageDownloadAdapter adapter = downloadAdapter == null
                ? new JdkCoverImageDownloadAdapter(downloadTimeout())
                : downloadAdapter;
        try (CoverImageDownloadAdapter.CoverImageDownloadResponse response = adapter.download(remoteUri);
                InputStream stream = response.body();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new SecurityException("Redirect is not allowed for image download");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Download failed with HTTP " + response.statusCode());
            }
            String contentType = normalizedContentType(response);
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new SecurityException("Unsupported image content type: " + contentType);
            }
            Long declaredLengthHeader = response.firstHeaderAsLong("Content-Length");
            long declaredLength = declaredLengthHeader == null ? -1 : declaredLengthHeader;
            if (declaredLength > MAX_DOWNLOAD_BYTES) {
                throw new SecurityException("File exceeds maximum allowed size of 10MB");
            }
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new SecurityException("File exceeds maximum allowed size of 10MB");
                }
                out.write(buffer, 0, read);
            }
            return new DownloadedImage(out.toByteArray(), contentType);
        }
    }

    private URI validateDownloadUri(String remoteUrl) {
        URI uri;
        try {
            uri = URI.create(remoteUrl);
        } catch (IllegalArgumentException ex) {
            throw new SecurityException("Invalid image URL", ex);
        }
        try {
            outboundUrlPolicy.validateImageDownloadStrict(uri, strictPolicy());
        } catch (OutboundHttpException ex) {
            throw new SecurityException("Unsafe image URL is blocked: " + ex.getMessage(), ex);
        }
        return uri;
    }

    private OutboundHttpPolicy strictPolicy() {
        return OutboundHttpPolicy.imageDownloadStrict(downloadTimeout(), allowLocalAddresses);
    }

    private Duration downloadTimeout() {
        return Duration.ofSeconds(downloadTimeoutSeconds);
    }

    private String normalizedContentType(CoverImageDownloadAdapter.CoverImageDownloadResponse response) {
        String header = response.firstHeader("Content-Type");
        return java.util.Optional.ofNullable(header)
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new SecurityException("Image Content-Type is required"));
    }

    private record DownloadedImage(byte[] data, String contentType) {
    }
}
