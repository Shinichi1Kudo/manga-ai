package com.manga.ai.series.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.enums.SeriesStatus;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.role.entity.Role;
import com.manga.ai.nlp.service.NLPExtractService;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.dto.SeriesDetailVO;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.series.service.SeriesService;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.shot.mapper.ShotVideoAssetMapper;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeriesServiceImplTest {

    @Test
    void lockSeriesRejectsConfirmedRolesWithoutUsableActiveAssets() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, roleMapper, roleAssetMapper);
        Series series = new Series();
        series.setId(12L);
        series.setUserId(7L);
        series.setStatus(SeriesStatus.PENDING_REVIEW.getCode());
        when(seriesMapper.selectById(12L)).thenReturn(series);
        when(roleMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(roleAssetMapper.countRolesWithoutUsableActiveAssetBySeriesId(12L)).thenReturn(2L);

        UserContextHolder.setUserId(7L);
        try {
            assertThatThrownBy(() -> service.lockSeries(12L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("存在 2 个角色图片未生成成功，无法锁定");
        } finally {
            UserContextHolder.clear();
        }

        verify(roleAssetMapper).countRolesWithoutUsableActiveAssetBySeriesId(12L);
        verify(seriesMapper, never()).updateById(any(Series.class));
    }

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

        verify(seriesMapper, never()).selectSeriesCount(any());
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
    void getSeriesListLetsUserOneViewAllSeries() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, mock(RoleMapper.class));

        UserContextHolder.setUserId(1L);
        try {
            service.getSeriesList(1, 9);
        } finally {
            UserContextHolder.clear();
        }

        verify(seriesMapper).selectSeriesListCards(isNull(), eq(9), eq(0));
    }

    @Test
    void getSeriesCountLetsUserOneCountAllSeries() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, mock(RoleMapper.class));
        when(seriesMapper.selectSeriesCount(null)).thenReturn(42);

        UserContextHolder.setUserId(1L);
        Integer total;
        try {
            total = service.getSeriesCount();
        } finally {
            UserContextHolder.clear();
        }

        assertThat(total).isEqualTo(42);
        verify(seriesMapper).selectSeriesCount(isNull());
        verify(seriesMapper, never()).selectCount(any(Wrapper.class));
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

    @Test
    void getSeriesDetailLetsUserOneViewAnotherUsersSeries() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, roleMapper);
        Series series = new Series();
        series.setId(12L);
        series.setUserId(8L);
        when(seriesMapper.selectById(12L)).thenReturn(series);
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        UserContextHolder.setUserId(1L);
        try {
            service.getSeriesDetail(12L);
        } finally {
            UserContextHolder.clear();
        }

        verify(seriesMapper).selectById(12L);
        verify(roleMapper).selectList(any(Wrapper.class));
    }

    @Test
    void getLockedSeriesUsesLightweightLockedSeriesMapper() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, roleMapper);
        SeriesDetailVO lockedSeries = new SeriesDetailVO();
        lockedSeries.setId(21L);
        lockedSeries.setSeriesName("只查轻量字段");
        lockedSeries.setRoleCount(4);
        when(seriesMapper.selectLockedSeriesCards(7L)).thenReturn(List.of(lockedSeries));

        UserContextHolder.setUserId(7L);
        List<SeriesDetailVO> result;
        try {
            result = service.getLockedSeries();
        } finally {
            UserContextHolder.clear();
        }

        assertThat(result).containsExactly(lockedSeries);
        verify(seriesMapper).selectLockedSeriesCards(eq(7L));
        verify(seriesMapper, never()).selectList(any(Wrapper.class));
        verify(roleMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void getLockedSeriesLetsUserOneViewAllLockedSeries() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        SeriesServiceImpl service = createService(seriesMapper, roleMapper);
        when(seriesMapper.selectLockedSeriesCards(null)).thenReturn(List.of());

        UserContextHolder.setUserId(1L);
        try {
            service.getLockedSeries();
        } finally {
            UserContextHolder.clear();
        }

        verify(seriesMapper).selectLockedSeriesCards(isNull());
        verify(seriesMapper, never()).selectList(any(Wrapper.class));
        verify(roleMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void asyncProcessCharactersWithUploadedImageCreatesManualAssetAndSkipsImageGeneration() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        AssetMetadataMapper assetMetadataMapper = mock(AssetMetadataMapper.class);
        ImageGenerateService imageGenerateService = mock(ImageGenerateService.class);
        SeriesServiceImpl service = createService(
                seriesMapper,
                roleMapper,
                roleAssetMapper,
                assetMetadataMapper,
                imageGenerateService
        );
        Series series = new Series();
        series.setId(12L);
        series.setStyleKeywords("realistic");
        when(seriesMapper.selectById(12L)).thenReturn(series);
        when(roleMapper.insert(any(Role.class))).thenAnswer(invocation -> {
            invocation.<Role>getArgument(0).setId(88L);
            return 1;
        });
        when(roleAssetMapper.insert(any(RoleAsset.class))).thenAnswer(invocation -> {
            invocation.<RoleAsset>getArgument(0).setId(99L);
            return 1;
        });

        service.asyncProcessCharacters(12L, """
                [{
                    "roleName": "林墨",
                    "customPrompt": "年轻程序员",
                    "originalPrompt": "年轻程序员",
                    "aspectRatio": "9:16",
                    "uploadedImageUrl": "https://movie-agent.oss-cn-beijing.aliyuncs.com/characters/linmo.png"
                }]
                """);

        ArgumentCaptor<RoleAsset> assetCaptor = ArgumentCaptor.forClass(RoleAsset.class);
        verify(roleAssetMapper).insert(assetCaptor.capture());
        RoleAsset asset = assetCaptor.getValue();
        assertThat(asset.getRoleId()).isEqualTo(88L);
        assertThat(asset.getClothingName()).isEqualTo("默认");
        assertThat(asset.getFilePath()).isEqualTo("https://movie-agent.oss-cn-beijing.aliyuncs.com/characters/linmo.png");
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.PENDING_REVIEW.getCode());

        ArgumentCaptor<AssetMetadata> metadataCaptor = ArgumentCaptor.forClass(AssetMetadata.class);
        verify(assetMetadataMapper).insert(metadataCaptor.capture());
        assertThat(metadataCaptor.getValue().getAssetId()).isEqualTo(99L);
        assertThat(metadataCaptor.getValue().getModelVersion()).isEqualTo("manual-upload");
        assertThat(metadataCaptor.getValue().getAspectRatio()).isEqualTo("9:16");

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleMapper).updateById(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getStatus()).isEqualTo(RoleStatus.PENDING_REVIEW.getCode());
        verify(imageGenerateService, never()).generateCharacterSheet(any());
    }

    private SeriesServiceImpl createService(SeriesMapper seriesMapper, RoleMapper roleMapper) {
        return createService(seriesMapper, roleMapper, mock(RoleAssetMapper.class));
    }

    private SeriesServiceImpl createService(SeriesMapper seriesMapper, RoleMapper roleMapper, RoleAssetMapper roleAssetMapper) {
        return createService(seriesMapper, roleMapper, roleAssetMapper, mock(AssetMetadataMapper.class), mock(ImageGenerateService.class));
    }

    private SeriesServiceImpl createService(SeriesMapper seriesMapper,
                                            RoleMapper roleMapper,
                                            RoleAssetMapper roleAssetMapper,
                                            AssetMetadataMapper assetMetadataMapper,
                                            ImageGenerateService imageGenerateService) {
        return new SeriesServiceImpl(
                seriesMapper,
                roleMapper,
                roleAssetMapper,
                assetMetadataMapper,
                mock(NLPExtractService.class),
                imageGenerateService,
                mock(SimpMessagingTemplate.class),
                mock(SeriesService.class),
                mock(EpisodeMapper.class),
                mock(ShotMapper.class),
                mock(ShotVideoAssetMapper.class),
                mock(OssService.class)
        );
    }
}
