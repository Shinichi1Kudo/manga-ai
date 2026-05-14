package com.manga.ai.series.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.nlp.service.NLPExtractService;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.series.service.SeriesService;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.shot.mapper.ShotVideoAssetMapper;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeriesServiceImplTest {

    @Test
    void getSeriesListRequiresAuthenticatedUserBeforeQuerying() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, mock(RoleMapper.class));

        assertThatThrownBy(() -> service.getSeriesList(1, 9))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先登录");

        verify(seriesMapper, never()).selectSeriesListCards(any(), any(), any());
    }

    @Test
    void getSeriesCountRequiresAuthenticatedUserBeforeCounting() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, mock(RoleMapper.class));

        assertThatThrownBy(service::getSeriesCount)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先登录");

        verify(seriesMapper, never()).selectCount(any(Wrapper.class));
    }

    @Test
    void getSeriesListPassesCurrentUserToMapper() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, mock(RoleMapper.class));

        UserContextHolder.setUserId(7L);
        try {
            service.getSeriesList(2, 9);
        } finally {
            UserContextHolder.clear();
        }

        verify(seriesMapper).selectSeriesListCards(eq(7L), eq(9), eq(9));
    }

    @Test
    void getSeriesDetailRejectsSeriesOwnedByAnotherUser() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, roleMapper);
        Series series = new Series();
        series.setId(12L);
        series.setUserId(8L);
        when(seriesMapper.selectById(12L)).thenReturn(series);

        UserContextHolder.setUserId(7L);
        try {
            assertThatThrownBy(() -> service.getSeriesDetail(12L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("系列不存在");
        } finally {
            UserContextHolder.clear();
        }

        verify(seriesMapper).selectById(12L);
        verifyNoInteractions(roleMapper);
    }

    private SeriesServiceImpl createService(SeriesMapper seriesMapper, RoleMapper roleMapper) {
        return new SeriesServiceImpl(
                seriesMapper,
                roleMapper,
                mock(RoleAssetMapper.class),
                mock(AssetMetadataMapper.class),
                mock(NLPExtractService.class),
                mock(ImageGenerateService.class),
                mock(SimpMessagingTemplate.class),
                mock(SeriesService.class),
                mock(EpisodeMapper.class),
                mock(ShotMapper.class),
                mock(ShotVideoAssetMapper.class),
                mock(OssService.class)
        );
    }
}
