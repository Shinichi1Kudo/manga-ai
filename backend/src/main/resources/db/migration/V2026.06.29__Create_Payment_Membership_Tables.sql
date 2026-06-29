CREATE TABLE IF NOT EXISTS payment_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    order_type VARCHAR(30) NOT NULL,
    product_code VARCHAR(80) NOT NULL,
    product_name VARCHAR(120) NOT NULL,
    amount_cents INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    channel VARCHAR(30) DEFAULT 'ALIPAY',
    qrcode TEXT,
    alipay_trade_no VARCHAR(80),
    app_id VARCHAR(64),
    seller_id VARCHAR(64),
    notify_id VARCHAR(128),
    trade_status VARCHAR(40),
    base_credits INT DEFAULT 0,
    bonus_credits INT DEFAULT 0,
    total_credits INT DEFAULT 0,
    membership_tier VARCHAR(30),
    membership_period VARCHAR(30),
    membership_months INT DEFAULT 0,
    paid_at TIMESTAMP NULL,
    credited_at TIMESTAMP NULL,
    raw_notify TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_user_status ON payment_order (user_id, status, created_at);

CREATE TABLE IF NOT EXISTS user_membership (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tier VARCHAR(30) NOT NULL,
    product_code VARCHAR(80) NOT NULL,
    period VARCHAR(30) NOT NULL,
    months INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_order_no VARCHAR(64) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_membership_user_expires ON user_membership (user_id, status, expires_at);

CREATE TABLE IF NOT EXISTS credit_balance_bucket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_order_no VARCHAR(64),
    total_amount INT NOT NULL,
    remaining_amount INT NOT NULL,
    expires_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_credit_bucket_user_expires ON credit_balance_bucket (user_id, expires_at, remaining_amount);
