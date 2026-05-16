package com.manga.ai.role.service.impl;

import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.enums.SeriesStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.role.dto.RoleCreateRequest;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleAttributeMapper;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoleServiceImplTest {

    @AfterEach
    void clearUserContext() {
        UserContextHolder.clear();
    }

    @Test
    void createRoleWithUploadedImageCreatesPendingAssetAndSkipsGenerationAndCredits() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        ImageGenerateService imageGenerateService = mock(ImageGenerateService.class);
        UserService userService = mock(UserService.class);
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any(Role.class))).thenAnswer(invocation -> {
            invocation.<Role>getArgument(0).setId(42L);
            return 1;
        });

        RoleServiceImpl service = createService(roleMapper, roleAssetMapper, seriesMapper, imageGenerateService, userService);
        RoleCreateRequest request = new RoleCreateRequest();
        request.setSeriesId(12L);
        request.setRoleName("沈清欢");
        request.setOriginalPrompt("黑色长发，冷静果断");
        request.setUploadedImageUrl("https://movie-agent.oss-cn-beijing.aliyuncs.com/characters/uploaded.png");

        UserContextHolder.setUserId(7L);
        Long roleId = service.createRole(request);

        assertThat(roleId).isEqualTo(42L);
        ArgumentCaptor<RoleAsset> assetCaptor = ArgumentCaptor.forClass(RoleAsset.class);
        verify(roleAssetMapper).insert(assetCaptor.capture());
        RoleAsset asset = assetCaptor.getValue();
        assertThat(asset.getRoleId()).isEqualTo(42L);
        assertThat(asset.getClothingName()).isEqualTo("默认");
        assertThat(asset.getFilePath()).isEqualTo(request.getUploadedImageUrl());
        assertThat(asset.getThumbnailPath()).isEqualTo(request.getUploadedImageUrl());
        assertThat(asset.getTransparentPath()).isEqualTo(request.getUploadedImageUrl());
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.PENDING_REVIEW.getCode());
        assertThat(asset.getIsActive()).isEqualTo(1);
        assertThat(asset.getValidationPassed()).isEqualTo(1);

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleMapper).updateById(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getStatus()).isEqualTo(RoleStatus.PENDING_REVIEW.getCode());
        verify(userService, never()).deductCredits(any(), any(Integer.class), any(), any(), any(), any());
        verify(imageGenerateService, never()).generateCharacterAssets(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirmRoleRejectsRoleWithoutUsableActiveAsset() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        Role role = new Role();
        role.setId(10L);
        role.setSeriesId(20L);
        role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
        when(roleMapper.selectById(10L)).thenReturn(role);
        when(roleAssetMapper.countUsableActiveAssetsByRoleId(10L)).thenReturn(0L);

        assertThatThrownBy(() -> createService(roleMapper, roleAssetMapper, seriesMapper).confirmRole(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色图片未全部生成成功，无法确认");

        verify(roleAssetMapper).countUsableActiveAssetsByRoleId(10L);
        verify(roleMapper, never()).updateById(any(Role.class));
    }

    @Test
    void unlockRoleUsesLightweightStatusUpdatesWithoutLoadingFullEntities() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        Role role = new Role();
        role.setId(10L);
        role.setSeriesId(20L);
        role.setStatus(RoleStatus.LOCKED.getCode());
        when(roleMapper.selectUnlockStateById(10L)).thenReturn(role);
        when(roleMapper.updateStatusIfUnlockable(
                eq(10L),
                eq(RoleStatus.PENDING_REVIEW.getCode()),
                any(LocalDateTime.class),
                eq(RoleStatus.CONFIRMED.getCode()),
                eq(RoleStatus.LOCKED.getCode())
        )).thenReturn(1);

        createService(roleMapper, seriesMapper).unlockRole(10L);

        verify(roleMapper).selectUnlockStateById(10L);
        verify(roleMapper).updateStatusIfUnlockable(
                eq(10L),
                eq(RoleStatus.PENDING_REVIEW.getCode()),
                any(LocalDateTime.class),
                eq(RoleStatus.CONFIRMED.getCode()),
                eq(RoleStatus.LOCKED.getCode())
        );
        verify(seriesMapper).markLockedSeriesPendingReview(
                eq(20L),
                eq(SeriesStatus.PENDING_REVIEW.getCode()),
                eq(SeriesStatus.LOCKED.getCode()),
                any(LocalDateTime.class)
        );
        verify(roleMapper, never()).selectById(10L);
        verify(roleMapper, never()).updateById(any(Role.class));
        verify(seriesMapper, never()).selectById(20L);
        verify(seriesMapper, never()).updateById(any(Series.class));
    }

    @Test
    void unlockRoleRejectsPendingRoleBeforeUpdating() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        Role role = new Role();
        role.setId(10L);
        role.setSeriesId(20L);
        role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
        when(roleMapper.selectUnlockStateById(10L)).thenReturn(role);

        assertThatThrownBy(() -> createService(roleMapper, seriesMapper).unlockRole(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态不支持解锁操作");

        verify(roleMapper).selectUnlockStateById(10L);
        verify(roleMapper, never()).updateStatusIfUnlockable(any(), any(), any(), any(), any());
        verifyNoInteractions(seriesMapper);
    }

    private RoleServiceImpl createService(RoleMapper roleMapper, SeriesMapper seriesMapper) {
        return createService(roleMapper, mock(RoleAssetMapper.class), seriesMapper);
    }

    private RoleServiceImpl createService(RoleMapper roleMapper, RoleAssetMapper roleAssetMapper, SeriesMapper seriesMapper) {
        return createService(roleMapper, roleAssetMapper, seriesMapper, mock(ImageGenerateService.class), mock(UserService.class));
    }

    private RoleServiceImpl createService(RoleMapper roleMapper,
                                          RoleAssetMapper roleAssetMapper,
                                          SeriesMapper seriesMapper,
                                          ImageGenerateService imageGenerateService,
                                          UserService userService) {
        return new RoleServiceImpl(
                roleMapper,
                mock(RoleAttributeMapper.class),
                roleAssetMapper,
                seriesMapper,
                imageGenerateService,
                mock(AssetService.class),
                mock(TransactionTemplate.class),
                mock(OssService.class),
                userService
        );
    }
}
