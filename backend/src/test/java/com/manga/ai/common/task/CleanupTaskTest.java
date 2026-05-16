package com.manga.ai.common.task;

import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.enums.SeriesStatus;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.gptimage.service.GptImage2Service;
import com.manga.ai.prop.mapper.PropMapper;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.series.mapper.SeriesMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CleanupTaskTest {

    @Test
    void cleanupStuckInitializingSeriesRestoresSeriesAndRolesAfterOneHour() {
        SeriesMapper seriesMapper = mock(SeriesMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        CleanupTask cleanupTask = new CleanupTask(
                seriesMapper,
                mock(EpisodeMapper.class),
                mock(SceneMapper.class),
                mock(PropMapper.class),
                mock(GptImage2Service.class),
                roleMapper
        );

        cleanupTask.cleanupStuckInitializingSeries();

        verify(roleMapper).restoreStuckInitializingRoles(
                eq(RoleStatus.PENDING_REVIEW.getCode()),
                eq(RoleStatus.EXTRACTING.getCode()),
                eq(SeriesStatus.INITIALIZING.getCode()),
                argThat(timeout -> timeout.isBefore(LocalDateTime.now().minusMinutes(59))),
                any(LocalDateTime.class)
        );
        verify(seriesMapper).restoreStuckInitializingSeries(
                eq(SeriesStatus.PENDING_REVIEW.getCode()),
                eq(SeriesStatus.INITIALIZING.getCode()),
                argThat(timeout -> timeout.isBefore(LocalDateTime.now().minusMinutes(59))),
                any(LocalDateTime.class)
        );
    }
}
