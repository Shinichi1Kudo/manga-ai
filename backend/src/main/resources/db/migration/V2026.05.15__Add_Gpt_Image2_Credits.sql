ALTER TABLE gpt_image2_task
    ADD COLUMN credit_cost INT DEFAULT 6 COMMENT '本次生成扣除积分';

ALTER TABLE gpt_image2_task
    ADD COLUMN credits_refunded TINYINT(1) DEFAULT 0 COMMENT '积分是否已返还';
