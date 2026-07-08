package com.prodigalgal.ircs.content.auxiliary.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainRequest;
import com.prodigalgal.ircs.content.auxiliary.infrastructure.JdbcAuxiliaryAdminRepository;
import com.prodigalgal.ircs.content.video.api.ContentApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuxiliaryAdminServiceTest {

    @Mock
    private JdbcAuxiliaryAdminRepository repository;

    @Test
    void createPlaylistRejectsExistingIdLikeV1() {
        AuxiliaryAdminService service = new AuxiliaryAdminService(repository);
        UUID id = UUID.randomUUID();
        PlaylistRequest request = new PlaylistRequest(id, "Playlist", UUID.randomUUID(), null, List.of());

        assertThatThrownBy(() -> service.createPlaylist(request))
                .isInstanceOf(ContentApiException.class)
                .hasMessageContaining("cannot already have an ID");
        verify(repository, never()).createPlaylist(request);
    }

    @Test
    void sourceDomainUpdateRequiresBodyIdLikeV1() {
        AuxiliaryAdminService service = new AuxiliaryAdminService(repository);
        UUID id = UUID.randomUUID();
        SourceDomainRequest request = new SourceDomainRequest(
                null,
                null,
                "https://cdn.example.test",
                null,
                null,
                null);

        assertThatThrownBy(() -> service.updateSourceDomain(id, request))
                .isInstanceOf(ContentApiException.class)
                .hasMessageContaining("Invalid id");
        verify(repository, never()).updateSourceDomain(id, request);
    }
}
