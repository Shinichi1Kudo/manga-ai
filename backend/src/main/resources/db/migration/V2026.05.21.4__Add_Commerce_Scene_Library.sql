CREATE TABLE IF NOT EXISTS commerce_scene (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    scene_type VARCHAR(60) NOT NULL,
    library_type VARCHAR(60) NOT NULL,
    prompt TEXT,
    image_url VARCHAR(1024),
    aspect_ratio VARCHAR(10) DEFAULT '16:9',
    resolution VARCHAR(10) DEFAULT '2K',
    status VARCHAR(30) NOT NULL DEFAULT 'generating',
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_commerce_scene_series_updated (series_id, updated_at),
    INDEX idx_commerce_scene_user_status (user_id, status)
);

ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS scene_image_urls_json TEXT COMMENT '试穿项目引用的场景图URL列表JSON';
