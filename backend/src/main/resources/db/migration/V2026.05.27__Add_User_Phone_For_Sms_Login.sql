ALTER TABLE user ADD COLUMN IF NOT EXISTS phone VARCHAR(20) DEFAULT NULL COMMENT '手机号';
CREATE INDEX idx_user_phone ON user (phone);

ALTER TABLE user MODIFY COLUMN email VARCHAR(100) NULL;

ALTER TABLE email_verification ADD COLUMN IF NOT EXISTS phone VARCHAR(20) DEFAULT NULL COMMENT '手机号';
ALTER TABLE email_verification MODIFY COLUMN email VARCHAR(100) NULL;
CREATE INDEX idx_email_verification_phone_type ON email_verification (phone, type, used, created_at);
