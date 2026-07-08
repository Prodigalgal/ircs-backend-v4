package com.prodigalgal.ircs.storage.image;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.storage.StorageWorkPublisher;
import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CoverImageDownloadServiceTest {

    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00
    };

    @Mock
    private CoverImageAdminRepository repository;

    @Mock
    private CoverImageUrlResolver urlResolver;

    @Mock
    private FileNormalizationService normalizationService;

    @Mock
    private LocalObjectStorage localObjectStorage;

    @Mock
    private R2ObjectStorage r2ObjectStorage;

    @Mock
    private StorageWorkPublisher storageWorkPublisher;

    private final FakeResolver resolver = new FakeResolver();
    private final FakeDownloadAdapter downloadAdapter = new FakeDownloadAdapter();

    @Test
    void trueDownloadStoresLocalCoverAndFinalizesMetadata() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        NormalizedFile normalized =
                new NormalizedFile(PNG, "hash", "image/png", ".png", PNG.length, "covers/hash.png");
        downloadAdapter.enqueue(response(200, Map.of("Content-Type", List.of("image/png")), PNG));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("https://images.example.test/cover.png");
        when(normalizationService.normalize(any(byte[].class), eq("image/png"), eq("covers"))).thenReturn(normalized);
        when(localObjectStorage.exists("covers/hash.png")).thenReturn(false);
        when(r2ObjectStorage.isActive()).thenReturn(false);

        newService(true, false, 3).process(imageId);

        verify(normalizationService).normalize(aryEq(PNG), eq("image/png"), eq("covers"));
        verify(localObjectStorage).store(PNG, "covers/hash.png", "image/png");
        verify(repository).finalizeDownload(imageId, normalized);
        verify(storageWorkPublisher, never()).enqueueCoverR2Sync(any(), any());
        verify(repository, never()).markFailed(any(), any(), anyInt());
    }

    @Test
    void trueDownloadQueuesCoverR2SyncWhenR2IsActive() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        NormalizedFile normalized =
                new NormalizedFile(PNG, "hash", "image/png", ".png", PNG.length, "covers/hash.png");
        downloadAdapter.enqueue(response(200, Map.of("Content-Type", List.of("image/png")), PNG));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("https://images.example.test/cover.png");
        when(normalizationService.normalize(any(byte[].class), eq("image/png"), eq("covers"))).thenReturn(normalized);
        when(localObjectStorage.exists("covers/hash.png")).thenReturn(true);
        when(r2ObjectStorage.isActive()).thenReturn(true);

        newService(true, false, 3).process(imageId);

        verify(repository).finalizeDownload(imageId, normalized);
        verify(storageWorkPublisher).enqueueCoverR2Sync(imageId, "cover-download");
    }

    @Test
    void failedDownloadRecordsRetryFailureWithoutStoringObject() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        downloadAdapter.enqueue(response(500, Map.of("Content-Type", List.of("text/plain")), "failed".getBytes()));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("https://images.example.test/cover.png");

        newService(true, false, 3).process(imageId);

        verify(repository).markFailed(imageId, "Download failed with HTTP 500", 3);
        verifyNoInteractions(normalizationService, localObjectStorage);
        verify(repository, never()).finalizeDownload(any(), any());
    }

    @Test
    void unsafeLocalAddressIsBlockedBeforeDownloadAndDoesNotStoreObject() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        resolver.address = "127.0.0.1";
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("http://images.example.test/cover.png");

        newService(true, false, 1).process(imageId);

        verify(repository).markFailed(eq(imageId), contains("Unsafe image URL is blocked"), eq(1));
        verifyNoInteractions(normalizationService, localObjectStorage);
        verify(repository, never()).finalizeDownload(any(), any());
        org.assertj.core.api.Assertions.assertThat(downloadAdapter.requestedUris).isEmpty();
    }

    @Test
    void allowLocalAddressesPreservesExplicitDevEscapeHatch() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        NormalizedFile normalized =
                new NormalizedFile(PNG, "hash", "image/png", ".png", PNG.length, "covers/hash.png");
        resolver.address = "127.0.0.1";
        downloadAdapter.enqueue(response(200, Map.of("Content-Type", List.of("image/png")), PNG));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("http://images.example.test/cover.png");
        when(normalizationService.normalize(any(byte[].class), eq("image/png"), eq("covers"))).thenReturn(normalized);
        when(localObjectStorage.exists("covers/hash.png")).thenReturn(true);
        when(r2ObjectStorage.isActive()).thenReturn(false);

        newService(true, true, 3).process(imageId);

        verify(repository).finalizeDownload(imageId, normalized);
        org.assertj.core.api.Assertions.assertThat(downloadAdapter.requestedUris).hasSize(1);
    }

    @Test
    void redirectResponseIsRejectedWithoutStoringObject() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.FAILED, 1);
        downloadAdapter.enqueue(response(
                302,
                Map.of("Content-Type", List.of("image/png"), "Location", List.of("/next.png")),
                new byte[0]));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("https://images.example.test/cover.png");

        newService(true, false, 3).process(imageId);

        verify(repository).markFailed(eq(imageId), contains("Redirect is not allowed"), eq(3));
        verifyNoInteractions(normalizationService, localObjectStorage);
    }

    @Test
    void unsupportedContentTypeIsRejectedBeforeNormalization() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        downloadAdapter.enqueue(response(
                200,
                Map.of("Content-Type", List.of("text/html; charset=utf-8")),
                "<html></html>".getBytes()));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("https://images.example.test/cover.png");

        newService(true, false, 3).process(imageId);

        verify(repository).markFailed(eq(imageId), contains("Unsupported image content type"), eq(3));
        verifyNoInteractions(normalizationService, localObjectStorage);
    }

    @Test
    void declaredContentLengthOverLimitIsRejectedBeforeBodyRead() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        downloadAdapter.enqueue(response(
                200,
                Map.of(
                        "Content-Type", List.of("image/png"),
                        "Content-Length", List.of(String.valueOf(10L * 1024 * 1024 + 1))),
                PNG));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("https://images.example.test/cover.png");

        newService(true, false, 3).process(imageId);

        verify(repository).markFailed(eq(imageId), contains("File exceeds maximum allowed size of 10MB"), eq(3));
        verifyNoInteractions(normalizationService, localObjectStorage);
    }

    @Test
    void actualBodyOverLimitIsRejectedWhileStreaming() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.UNPROCESSED, 0);
        byte[] overLimit = new byte[(10 * 1024 * 1024) + 1];
        downloadAdapter.enqueue(response(200, Map.of("Content-Type", List.of("image/png")), overLimit));
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(true);
        when(urlResolver.resolve(row)).thenReturn("https://images.example.test/cover.png");

        newService(true, false, 3).process(imageId);

        verify(repository).markFailed(eq(imageId), contains("File exceeds maximum allowed size of 10MB"), eq(3));
        verifyNoInteractions(normalizationService, localObjectStorage);
    }

    @Test
    void disabledGateSkipsWithoutTouchingMetadata() {
        newService(false, false, 3).process(UUID.randomUUID());

        verifyNoInteractions(repository, urlResolver, normalizationService, localObjectStorage);
    }

    @Test
    void nonEligibleRecordIsSkippedAfterMarkFetchingFails() {
        UUID imageId = UUID.randomUUID();
        CoverImageRow row = row(imageId, CoverImageStatus.DEAD, 3);
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row));
        when(repository.markFetching(imageId)).thenReturn(false);

        newService(true, false, 3).process(imageId);

        verifyNoInteractions(urlResolver, normalizationService, localObjectStorage);
        verify(repository, never()).markFailed(any(), any(), anyInt());
    }

    private CoverImageDownloadService newService(boolean downloadEnabled, boolean allowLocalAddresses, int maxRetries) {
        CoverImageDownloadService service = CoverImageDownloadService.forTest(
                repository,
                urlResolver,
                normalizationService,
                localObjectStorage,
                r2ObjectStorage,
                storageWorkPublisher,
                new OutboundUrlPolicy(resolver),
                downloadAdapter);
        ReflectionTestUtils.setField(service, "coverPathPrefix", "covers");
        ReflectionTestUtils.setField(service, "downloadEnabled", downloadEnabled);
        ReflectionTestUtils.setField(service, "allowLocalAddresses", allowLocalAddresses);
        ReflectionTestUtils.setField(service, "maxRetries", maxRetries);
        ReflectionTestUtils.setField(service, "downloadTimeoutSeconds", 2);
        return service;
    }

    private CoverImageDownloadAdapter.CoverImageDownloadResponse response(
            int status,
            Map<String, List<String>> headers,
            byte[] body) {
        return new CoverImageDownloadAdapter.CoverImageDownloadResponse(
                status,
                headers,
                new ByteArrayInputStream(body));
    }

    private CoverImageRow row(UUID id, CoverImageStatus status, int retryCount) {
        Instant now = Instant.now();
        return new CoverImageRow(
                id,
                CoverImageStorageType.EXTERNAL,
                status,
                "/cover.png",
                null,
                null,
                null,
                null,
                UUID.randomUUID(),
                "https://images.example.test",
                retryCount,
                null,
                now,
                now,
                now);
    }

    private static final class FakeResolver implements OutboundAddressResolver {

        private String address = "93.184.216.34";

        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
            return List.of(InetAddress.getByName(address));
        }
    }

    private static final class FakeDownloadAdapter implements CoverImageDownloadAdapter {

        private final Queue<CoverImageDownloadResponse> responses = new ArrayDeque<>();
        private final List<URI> requestedUris = new ArrayList<>();

        void enqueue(CoverImageDownloadResponse response) {
            responses.add(response);
        }

        @Override
        public CoverImageDownloadResponse download(URI uri) throws IOException {
            requestedUris.add(uri);
            return responses.remove();
        }
    }
}
