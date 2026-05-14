package com.manga.ai.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.enums.CreditTransactionType;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.result.PageResult;
import com.manga.ai.user.dto.AdminCreditRecordVO;
import com.manga.ai.user.dto.CreditAdminDashboardVO;
import com.manga.ai.user.dto.CreditUsagePointVO;
import com.manga.ai.user.dto.UserCreditBalanceVO;
import com.manga.ai.user.entity.CreditRecord;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.CreditRecordMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.service.CreditAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 积分管理后台服务实现
 */
@Service
@RequiredArgsConstructor
public class CreditAdminServiceImpl implements CreditAdminService {

    private static final DateTimeFormatter RECORD_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:00");
    private static final String ADMIN_NICKNAME = "工藤新一";
    private static final String ADMIN_EMAIL = "1198693014@qq.com";

    private final UserMapper userMapper;
    private final CreditRecordMapper creditRecordMapper;

    @Override
    public CreditAdminDashboardVO getDashboard(Long currentUserId, Integer hours, Integer recordPage, Integer recordPageSize) {
        User currentUser = userMapper.selectById(currentUserId);
        if (!isCreditAdmin(currentUser)) {
            throw new BusinessException(403, "无权访问积分管理后台");
        }

        int normalizedHours = normalizeRange(hours, 24, 1, 168);
        int normalizedPage = normalizeRange(recordPage, 1, 1, 10000);
        int normalizedPageSize = normalizeRange(recordPageSize, 20, 1, 100);
        LocalDateTime since = LocalDateTime.now().minusHours(normalizedHours - 1L).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime recentSince = LocalDateTime.now().minusDays(3);

        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>().orderByDesc(User::getCredits));
        List<CreditRecord> allRecords = creditRecordMapper.selectList(
                new LambdaQueryWrapper<CreditRecord>().orderByDesc(CreditRecord::getCreatedAt));

        CreditAdminDashboardVO dashboard = new CreditAdminDashboardVO();
        dashboard.setTotalUsers(users.size());
        dashboard.setTotalBalance(users.stream().map(User::getCredits).filter(Objects::nonNull).mapToInt(Integer::intValue).sum());
        dashboard.setTotalDeducted(sumByType(allRecords, CreditTransactionType.DEDUCT.getCode()));
        dashboard.setTotalRewarded(sumByType(allRecords, CreditTransactionType.REWARD.getCode()));
        dashboard.setTotalRefunded(sumByType(allRecords, CreditTransactionType.REFUND.getCode()));
        dashboard.setTodayDeducted(sumTodayDeducted(allRecords));
        dashboard.setUsers(buildUserBalances(users, allRecords));
        dashboard.setTodayUserDeducted(buildTodayUserDeducted(users, allRecords));
        dashboard.setHourlyUsage(buildHourlyUsage(allRecords, since, normalizedHours));
        dashboard.setRecentRecords(buildRecentRecords(allRecords, users, recentSince, normalizedPage, normalizedPageSize));
        return dashboard;
    }

    private boolean isCreditAdmin(User user) {
        if (user == null) {
            return false;
        }
        return ADMIN_NICKNAME.equals(user.getNickname()) || ADMIN_EMAIL.equalsIgnoreCase(user.getEmail());
    }

    private int normalizeRange(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private int sumByType(List<CreditRecord> records, String type) {
        return records.stream()
                .filter(record -> type.equals(record.getType()))
                .map(CreditRecord::getAmount)
                .filter(Objects::nonNull)
                .mapToInt(amount -> Math.abs(Math.min(amount, 0)) + Math.max(amount, 0))
                .sum();
    }

    private int sumTodayDeducted(List<CreditRecord> records) {
        LocalDate today = LocalDate.now();
        return records.stream()
                .filter(record -> CreditTransactionType.DEDUCT.getCode().equals(record.getType()))
                .filter(record -> record.getCreatedAt() != null && record.getCreatedAt().toLocalDate().equals(today))
                .map(CreditRecord::getAmount)
                .filter(Objects::nonNull)
                .mapToInt(amount -> Math.abs(Math.min(amount, 0)))
                .sum();
    }

    private List<UserCreditBalanceVO> buildUserBalances(List<User> users, List<CreditRecord> records) {
        Map<Long, List<CreditRecord>> recordsByUser = records.stream()
                .filter(record -> record.getUserId() != null)
                .collect(Collectors.groupingBy(CreditRecord::getUserId));

        return users.stream().map(user -> {
            List<CreditRecord> userRecords = recordsByUser.getOrDefault(user.getId(), List.of());
            UserCreditBalanceVO vo = new UserCreditBalanceVO();
            vo.setUserId(user.getId());
            vo.setEmail(user.getEmail());
            vo.setNickname(user.getNickname());
            vo.setCredits(user.getCredits() == null ? 0 : user.getCredits());
            vo.setTotalDeducted(sumByType(userRecords, CreditTransactionType.DEDUCT.getCode()));
            vo.setTotalRewarded(sumByType(userRecords, CreditTransactionType.REWARD.getCode()));
            vo.setTotalRefunded(sumByType(userRecords, CreditTransactionType.REFUND.getCode()));
            userRecords.stream()
                    .map(CreditRecord::getCreatedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .ifPresent(time -> vo.setLastUsedAt(time.format(RECORD_TIME_FORMATTER)));
            return vo;
        }).collect(Collectors.toList());
    }

    private List<UserCreditBalanceVO> buildTodayUserDeducted(List<User> users, List<CreditRecord> records) {
        LocalDate today = LocalDate.now();
        Map<Long, User> userMap = users.stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, user -> user, (left, right) -> left));

        return records.stream()
                .filter(record -> CreditTransactionType.DEDUCT.getCode().equals(record.getType()))
                .filter(record -> record.getUserId() != null)
                .filter(record -> record.getCreatedAt() != null && record.getCreatedAt().toLocalDate().equals(today))
                .filter(record -> record.getAmount() != null)
                .collect(Collectors.groupingBy(CreditRecord::getUserId,
                        Collectors.summingInt(record -> Math.abs(record.getAmount()))))
                .entrySet().stream()
                .map(entry -> {
                    User user = userMap.get(entry.getKey());
                    UserCreditBalanceVO vo = new UserCreditBalanceVO();
                    vo.setUserId(entry.getKey());
                    if (user != null) {
                        vo.setEmail(user.getEmail());
                        vo.setNickname(user.getNickname());
                        vo.setCredits(user.getCredits() == null ? 0 : user.getCredits());
                    }
                    vo.setTotalDeducted(entry.getValue());
                    vo.setTotalRewarded(0);
                    vo.setTotalRefunded(0);
                    return vo;
                })
                .sorted(Comparator.comparing(UserCreditBalanceVO::getTotalDeducted,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    private List<CreditUsagePointVO> buildHourlyUsage(List<CreditRecord> records, LocalDateTime since, int hours) {
        Map<String, CreditUsagePointVO> points = new LinkedHashMap<>();
        for (int i = 0; i < hours; i++) {
            String label = since.plusHours(i).format(HOUR_FORMATTER);
            CreditUsagePointVO point = new CreditUsagePointVO();
            point.setLabel(label);
            point.setDeducted(0);
            point.setRewarded(0);
            point.setRefunded(0);
            points.put(label, point);
        }

        records.stream()
                .filter(record -> record.getCreatedAt() != null && !record.getCreatedAt().isBefore(since))
                .forEach(record -> {
                    String label = record.getCreatedAt().withMinute(0).withSecond(0).withNano(0).format(HOUR_FORMATTER);
                    CreditUsagePointVO point = points.get(label);
                    if (point == null || record.getAmount() == null) {
                        return;
                    }
                    int amount = Math.abs(record.getAmount());
                    if (CreditTransactionType.DEDUCT.getCode().equals(record.getType())) {
                        point.setDeducted(point.getDeducted() + amount);
                    } else if (CreditTransactionType.REWARD.getCode().equals(record.getType())) {
                        point.setRewarded(point.getRewarded() + amount);
                    } else if (CreditTransactionType.REFUND.getCode().equals(record.getType())) {
                        point.setRefunded(point.getRefunded() + amount);
                    }
                });

        return List.copyOf(points.values());
    }

    private PageResult<AdminCreditRecordVO> buildRecentRecords(
            List<CreditRecord> records, List<User> users, LocalDateTime recentSince, int page, int pageSize) {
        Map<Long, User> userMap = users.stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, user -> user, (left, right) -> left));

        List<CreditRecord> recentRecords = records.stream()
                .filter(record -> record.getCreatedAt() != null && !record.getCreatedAt().isBefore(recentSince))
                .collect(Collectors.toList());
        int total = recentRecords.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<AdminCreditRecordVO> voList = recentRecords.subList(fromIndex, toIndex).stream()
                .map(record -> convertRecord(record, userMap.get(record.getUserId())))
                .collect(Collectors.toList());
        return PageResult.of(voList, (long) total, (long) pageSize, (long) page);
    }

    private AdminCreditRecordVO convertRecord(CreditRecord record, User user) {
        AdminCreditRecordVO vo = new AdminCreditRecordVO();
        BeanUtils.copyProperties(record, vo);
        if (user != null) {
            vo.setEmail(user.getEmail());
            vo.setNickname(user.getNickname());
        }
        vo.setTypeDesc(resolveTypeDesc(record.getType()));
        vo.setUsageTypeDesc(resolveUsageTypeDesc(record.getUsageType()));
        if (record.getCreatedAt() != null) {
            vo.setCreatedAt(record.getCreatedAt().format(RECORD_TIME_FORMATTER));
        }
        return vo;
    }

    private String resolveTypeDesc(String type) {
        for (CreditTransactionType item : CreditTransactionType.values()) {
            if (item.getCode().equals(type)) {
                return item.getDesc();
            }
        }
        return type;
    }

    private String resolveUsageTypeDesc(String usageType) {
        if (usageType == null) {
            return null;
        }
        for (CreditUsageType item : CreditUsageType.values()) {
            if (item.getCode().equals(usageType)) {
                return item.getDesc();
            }
        }
        return usageType;
    }
}
