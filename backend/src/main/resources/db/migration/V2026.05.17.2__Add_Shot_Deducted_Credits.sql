ALTER TABLE shot
    ADD COLUMN deducted_credits INT DEFAULT NULL COMMENT '视频生成已扣除积分（用于失败返还）';
