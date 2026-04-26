ALTER TABLE shot ADD COLUMN generation_error VARCHAR(1000) DEFAULT NULL COMMENT '生成失败原因' AFTER generation_status;
