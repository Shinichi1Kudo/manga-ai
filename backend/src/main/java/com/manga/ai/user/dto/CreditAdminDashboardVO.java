package com.manga.ai.user.dto;

import com.manga.ai.common.result.PageResult;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理员积分看板数据
 */
@Data
public class CreditAdminDashboardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer totalUsers = 0;
    private Integer totalBalance = 0;
    private Integer totalDeducted = 0;
    private Integer totalRewarded = 0;
    private Integer totalRefunded = 0;
    private Integer todayDeducted = 0;

    private List<UserCreditBalanceVO> users = new ArrayList<>();
    private List<UserCreditBalanceVO> todayUserDeducted = new ArrayList<>();
    private List<CreditUsagePointVO> hourlyUsage = new ArrayList<>();
    private PageResult<AdminCreditRecordVO> recentRecords = PageResult.of(new ArrayList<>(), 0L, 20L, 1L);
}
