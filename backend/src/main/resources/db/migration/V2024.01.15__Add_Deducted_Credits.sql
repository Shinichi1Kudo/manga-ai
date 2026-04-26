-- 添加 deducted_credits 字段用于图像生成积分扣费

ALTER TABLE role ADD COLUMN deducted_credits INT DEFAULT NULL COMMENT '已扣除的积分（用于图像生成失败时返还）';

ALTER TABLE prop ADD COLUMN deducted_credits INT DEFAULT NULL COMMENT '已扣除的积分（用于图像生成失败时返还）';

ALTER TABLE scene ADD COLUMN deducted_credits INT DEFAULT NULL COMMENT '已扣除的积分（用于图像生成失败时返还）';
