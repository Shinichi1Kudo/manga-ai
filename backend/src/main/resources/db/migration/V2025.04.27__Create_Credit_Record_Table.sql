-- 创建积分记录表
CREATE TABLE IF NOT EXISTS `credit_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `amount` INT NOT NULL COMMENT '积分数量(扣费为负,返还为正)',
    `balance_after` INT NOT NULL COMMENT '交易后余额',
    `type` VARCHAR(20) NOT NULL COMMENT '类型: DEDUCT/REFUND/REWARD',
    `usage_type` VARCHAR(50) DEFAULT NULL COMMENT '用途: VIDEO_GENERATION/IMAGE_GENERATION/SCRIPT_PARSE等',
    `description` VARCHAR(255) NOT NULL COMMENT '描述: 如"视频生成-分镜1"',
    `reference_id` BIGINT DEFAULT NULL COMMENT '关联ID(shotId/roleId等)',
    `reference_type` VARCHAR(20) DEFAULT NULL COMMENT '关联类型: SHOT/ROLE/SCENE/PROP/EPISODE',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分记录表';
