package com.manga.ai.user.service;

import com.manga.ai.user.dto.CreditAdminDashboardVO;

/**
 * 积分管理后台服务
 */
public interface CreditAdminService {

    /**
     * 获取积分管理后台看板数据。
     */
    default CreditAdminDashboardVO getDashboard(Long currentUserId, Integer hours, Integer recordPage, Integer recordPageSize) {
        return getDashboard(currentUserId, hours, recordPage, recordPageSize, null);
    }

    /**
     * 获取积分管理后台看板数据，可按用户昵称模糊筛选最近流水。
     */
    CreditAdminDashboardVO getDashboard(Long currentUserId, Integer hours, Integer recordPage, Integer recordPageSize, String nickname);
}
