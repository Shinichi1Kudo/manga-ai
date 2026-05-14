package com.manga.ai.role.service.impl;

import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.enums.SeriesStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleAttributeMapper;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoleServiceImplTest {

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
        return new RoleServiceImpl(
                roleMapper,
                mock(RoleAttributeMapper.class),
                mock(RoleAssetMapper.class),
                seriesMapper,
                mock(ImageGenerateService.class),
                mock(AssetService.class),
                mock(TransactionTemplate.class),
                mock(OssService.class),
                mock(UserService.class)
        );
    }
}
