package com.manga.ai.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.user.dto.CreditAdminDashboardVO;
import com.manga.ai.user.entity.CreditRecord;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.CreditRecordMapper;
import com.manga.ai.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditAdminServiceImplTest {

    @Test
    void dashboardRejectsNonKudoShinichiUser() {
        UserMapper userMapper = mock(UserMapper.class);
        CreditRecordMapper creditRecordMapper = mock(CreditRecordMapper.class);
        CreditAdminServiceImpl service = new CreditAdminServiceImpl(userMapper, creditRecordMapper);

        User current = new User();
        current.setId(2L);
        current.setEmail("other@example.com");
        current.setNickname("普通用户");
        when(userMapper.selectById(2L)).thenReturn(current);

        assertThatThrownBy(() -> service.getDashboard(2L, 24, 1, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权访问积分管理后台");
    }

    @Test
    void dashboardAggregatesBalancesRecordsAndHourlyUsageForKudoShinichi() {
        UserMapper userMapper = mock(UserMapper.class);
        CreditRecordMapper creditRecordMapper = mock(CreditRecordMapper.class);
        CreditAdminServiceImpl service = new CreditAdminServiceImpl(userMapper, creditRecordMapper);

        User admin = user(1L, "1198693014@qq.com", "工藤新一", 10808);
        User user = user(2L, "creator@example.com", "创作者", 120);
        when(userMapper.selectById(1L)).thenReturn(admin);
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(admin, user));

        LocalDateTime now = LocalDateTime.now();
        CreditRecord deduct = record(1L, 2L, -64, 56, "deduct", "subject_replacement", "主体替换", now.minusHours(1));
        CreditRecord reward = record(2L, 2L, 100, 120, "reward", "redeem_code", "兑换码", now.minusHours(2));
        CreditRecord refund = record(3L, 1L, 6, 10808, "refund", null, "失败返还", now.minusHours(3));
        CreditRecord oldDeduct = record(4L, 2L, -32, 88, "deduct", "old", "三天前消耗", now.minusDays(4));
        when(creditRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(deduct, reward, refund, oldDeduct));

        CreditAdminDashboardVO dashboard = service.getDashboard(1L, 6, 1, 2);

        assertThat(dashboard.getTotalUsers()).isEqualTo(2);
        assertThat(dashboard.getTotalBalance()).isEqualTo(10928);
        assertThat(dashboard.getTotalDeducted()).isEqualTo(96);
        assertThat(dashboard.getTotalRewarded()).isEqualTo(100);
        assertThat(dashboard.getTotalRefunded()).isEqualTo(6);
        assertThat(dashboard.getTodayDeducted()).isEqualTo(64);
        assertThat(dashboard.getTodayUserDeducted()).hasSize(1);
        assertThat(dashboard.getTodayUserDeducted().get(0).getNickname()).isEqualTo("创作者");
        assertThat(dashboard.getTodayUserDeducted().get(0).getTotalDeducted()).isEqualTo(64);
        assertThat(dashboard.getUsers()).hasSize(2);
        assertThat(dashboard.getUsers().get(1).getTotalDeducted()).isEqualTo(96);
        assertThat(dashboard.getRecentRecords().getRecords()).hasSize(2);
        assertThat(dashboard.getRecentRecords().getTotal()).isEqualTo(3);
        assertThat(dashboard.getRecentRecords().getPages()).isEqualTo(2);
        assertThat(dashboard.getRecentRecords().getRecords().get(0).getNickname()).isEqualTo("创作者");
        assertThat(dashboard.getRecentRecords().getRecords())
                .noneMatch(record -> "三天前消耗".equals(record.getDescription()));
        assertThat(dashboard.getHourlyUsage()).hasSize(6);
        assertThat(dashboard.getHourlyUsage()).anyMatch(point -> point.getDeducted() == 64);
    }

    private User user(Long id, String email, String nickname, Integer credits) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setNickname(nickname);
        user.setCredits(credits);
        return user;
    }

    private CreditRecord record(Long id, Long userId, Integer amount, Integer balanceAfter,
                                String type, String usageType, String description, LocalDateTime createdAt) {
        CreditRecord record = new CreditRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setAmount(amount);
        record.setBalanceAfter(balanceAfter);
        record.setType(type);
        record.setUsageType(usageType);
        record.setDescription(description);
        record.setCreatedAt(createdAt);
        return record;
    }
}
