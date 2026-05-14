package com.manga.ai.user.service;

import com.manga.ai.user.dto.CreditAdminDashboardVO;

/**
 * 积分管理后台服务
 */
public interface CreditAdminService {

    /**
     * 获取积分管理后台看板数据。
     */
    CreditAdminDashboardVO getDashboard(Long currentUserId, Integer hours, Integer recordPage, Integer recordPageSize);
}
