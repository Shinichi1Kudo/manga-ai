ALTER TABLE subject_replacement_task
    ADD COLUMN deducted_credits INT DEFAULT NULL COMMENT '已扣除的积分（用于生成失败时返还）';

ALTER TABLE subject_replacement_task
    ADD COLUMN credits_refunded TINYINT(1) DEFAULT 0 COMMENT '积分是否已返还';
