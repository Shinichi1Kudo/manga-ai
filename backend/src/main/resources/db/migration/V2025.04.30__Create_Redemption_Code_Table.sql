-- 创建兑换码表
CREATE TABLE IF NOT EXISTS `redemption_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(32) NOT NULL COMMENT '兑换码（区分大小写）',
    `credits` INT NOT NULL COMMENT '积分数量',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-未使用，1-已使用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `used_at` DATETIME DEFAULT NULL COMMENT '使用时间',
    `used_by` BIGINT DEFAULT NULL COMMENT '使用者用户ID',
    `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注（如：推广活动、新用户奖励等）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_code` (`code`),
    INDEX `idx_status` (`status`),
    INDEX `idx_used_by` (`used_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='兑换码表';
