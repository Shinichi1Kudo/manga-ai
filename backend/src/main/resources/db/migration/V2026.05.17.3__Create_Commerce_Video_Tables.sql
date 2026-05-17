CREATE TABLE IF NOT EXISTS commerce_series (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    category VARCHAR(40) NOT NULL DEFAULT 'clothing',
    visual_style VARCHAR(40) NOT NULL DEFAULT 'realistic',
    default_aspect_ratio VARCHAR(10) NOT NULL DEFAULT '3:4',
    enabled_content_types VARCHAR(120) NOT NULL DEFAULT 'talking,try_on',
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_commerce_series_user_created (user_id, created_at),
    INDEX idx_commerce_series_user_status (user_id, status)
);

CREATE TABLE IF NOT EXISTS commerce_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    source_type VARCHAR(30) NOT NULL DEFAULT 'ai',
    gender_age VARCHAR(80),
    body_profile VARCHAR(200),
    style_tags VARCHAR(255),
    speaking_persona VARCHAR(200),
    appearance_prompt TEXT,
    base_image_url VARCHAR(1024),
    status VARCHAR(30) NOT NULL DEFAULT 'generating',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_commerce_model_series_created (series_id, created_at),
    INDEX idx_commerce_model_user_status (user_id, status)
);

CREATE TABLE IF NOT EXISTS commerce_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_id BIGINT NOT NULL,
    series_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    image_url VARCHAR(1024) NOT NULL,
    product_type VARCHAR(60),
    selling_points TEXT,
    fabric_fit_color_size TEXT,
    target_audience VARCHAR(255),
    price_offer VARCHAR(255),
    try_on_requirements TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_commerce_product_model_created (model_id, created_at),
    INDEX idx_commerce_product_series_created (series_id, created_at),
    INDEX idx_commerce_product_user_status (user_id, status)
);

CREATE TABLE IF NOT EXISTS commerce_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    model_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content_type VARCHAR(30) NOT NULL,
    aspect_ratio VARCHAR(10) NOT NULL DEFAULT '3:4',
    model_image_url VARCHAR(1024) NOT NULL,
    product_image_url VARCHAR(1024) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_commerce_project_product_created (product_id, created_at),
    INDEX idx_commerce_project_user_status (user_id, status)
);

CREATE TABLE IF NOT EXISTS commerce_shot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    title VARCHAR(120) NOT NULL,
    visual_description TEXT NOT NULL,
    voiceover_text TEXT,
    duration_seconds INT NOT NULL DEFAULT 5,
    video_url VARCHAR(1024),
    generation_status VARCHAR(30) NOT NULL DEFAULT 'draft',
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_commerce_shot_project_order (project_id, sort_order),
    INDEX idx_commerce_shot_generation_status (generation_status)
);
