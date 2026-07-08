package com.prodigalgal.ircs.content.auxiliary.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverRequest;
import com.prodigalgal.ircs.content.auxiliary.infrastructure.JdbcAuxiliaryAdminRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultResolverPresetSeederTest {

    @Mock
    private JdbcAuxiliaryAdminRepository repository;

    @Test
    void createsV1DefaultResolversWhenMissing() {
        DefaultResolverPresetSeeder seeder = DefaultResolverPresetSeeder.forTest(repository, true);

        int inserted = seeder.seedMissingResolvers();

        assertThat(inserted).isEqualTo(4);
        ArgumentCaptor<ResolverRequest> captor = ArgumentCaptor.forClass(ResolverRequest.class);
        verify(repository, org.mockito.Mockito.times(4)).createResolver(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ResolverRequest::name)
                .containsExactly("金蝉解析", "789解析", "火花解析", "麒麟解析");
        assertThat(captor.getAllValues())
                .extracting(ResolverRequest::remark)
                .containsExactly("支持多线路聚合解析", "速度较快，广告较少", "部分资源需授权", "综合性解析接口");
        assertThat(captor.getAllValues())
                .extracting(request -> request.lines().get(0).name())
                .containsOnly("默认线路");
        assertThat(captor.getAllValues())
                .extracting(request -> request.lines().get(0).url())
                .containsExactly(
                        "https://zy.jinchancaiji.com/?url=",
                        "https://www.789jiexi.com/?url=",
                        "https://cj.huohua.live/?url=",
                        "https://www.qilinzyz.com/?url=");
        assertThat(captor.getAllValues())
                .allSatisfy(request -> assertThat(request.activeValue()).isTrue());
    }

    @Test
    void skipsExistingNamesWithoutOverwritingRows() {
        when(repository.resolverExistsByName(anyString())).thenReturn(false);
        when(repository.resolverExistsByName("金蝉解析")).thenReturn(true);
        when(repository.resolverExistsByName("火花解析")).thenReturn(true);
        DefaultResolverPresetSeeder seeder = DefaultResolverPresetSeeder.forTest(repository, true);

        int inserted = seeder.seedMissingResolvers();

        assertThat(inserted).isEqualTo(2);
        ArgumentCaptor<ResolverRequest> captor = ArgumentCaptor.forClass(ResolverRequest.class);
        verify(repository, org.mockito.Mockito.times(2)).createResolver(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ResolverRequest::name)
                .containsExactly("789解析", "麒麟解析");
    }

    @Test
    void disabledGateSkipsAllRepositoryWrites() {
        DefaultResolverPresetSeeder seeder = DefaultResolverPresetSeeder.forTest(repository, false);

        int inserted = seeder.seedMissingResolvers();

        assertThat(inserted).isZero();
        verify(repository, never()).resolverExistsByName(any());
        verify(repository, never()).createResolver(any());
    }
}
