-- H2 数据库初始化脚本

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    credits INT DEFAULT 10,
    status INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);

-- 邮箱验证码表
CREATE TABLE IF NOT EXISTS email_verification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL,
    code VARCHAR(6) NOT NULL,
    type VARCHAR(20) NOT NULL,
    expired_at TIMESTAMP NOT NULL,
    used INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 系列表
CREATE TABLE IF NOT EXISTS series (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    series_name VARCHAR(100) NOT NULL,
    outline CLOB,
    background CLOB,
    character_intro CLOB,
    style_keywords VARCHAR(500),
    color_preference VARCHAR(200),
    art_style_ref VARCHAR(500),
    status INT DEFAULT 0,
    project_path VARCHAR(500),
    global_seed BIGINT,
    global_style_prompt CLOB,
    aspect_ratio VARCHAR(10),
    quality VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50),
    is_deleted INT DEFAULT 0,
    deleted_at TIMESTAMP
);

-- 角色表
CREATE TABLE IF NOT EXISTS role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    role_name VARCHAR(50) NOT NULL,
    role_code VARCHAR(20),
    status INT DEFAULT 0,
    age VARCHAR(20),
    gender VARCHAR(10),
    appearance CLOB,
    personality CLOB,
    clothing CLOB,
    special_marks CLOB,
    custom_prompt CLOB,
    original_prompt CLOB,
    style_keywords VARCHAR(500),
    original_text CLOB,
    extract_confidence DECIMAL(3,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_series_user_deleted_created ON series(user_id, is_deleted, created_at);
CREATE INDEX IF NOT EXISTS idx_role_series_deleted ON role(series_id, is_deleted);

-- 角色属性表
CREATE TABLE IF NOT EXISTS role_attribute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    attr_key VARCHAR(50),
    attr_value CLOB,
    confidence DECIMAL(3,2),
    source_type VARCHAR(20) DEFAULT 'NLP_EXTRACT',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 角色资产表
CREATE TABLE IF NOT EXISTS role_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    asset_type VARCHAR(20),
    view_type VARCHAR(20),
    clothing_id INT DEFAULT 1,
    version INT DEFAULT 1,
    file_path VARCHAR(500),
    transparent_path VARCHAR(500),
    thumbnail_path VARCHAR(500),
    file_name VARCHAR(200),
    status INT DEFAULT 0,
    is_active INT DEFAULT 1,
    validation_passed INT DEFAULT 0,
    validation_result CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 资产元数据表
CREATE TABLE IF NOT EXISTS asset_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL UNIQUE,
    prompt CLOB,
    user_prompt CLOB,
    negative_prompt CLOB,
    seed BIGINT,
    model_version VARCHAR(50) DEFAULT 'seedream-5.0-lite',
    image_width INT,
    image_height INT,
    aspect_ratio VARCHAR(10),
    detailed_view BOOLEAN DEFAULT FALSE,
    generation_time_ms BIGINT,
    api_response CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 异步任务队列表
CREATE TABLE IF NOT EXISTS task_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_type VARCHAR(50),
    ref_entity_type VARCHAR(20),
    ref_entity_id BIGINT,
    status INT DEFAULT 0,
    retry_count INT DEFAULT 0,
    max_retry INT DEFAULT 3,
    priority INT DEFAULT 0,
    input_data CLOB,
    output_data CLOB,
    error_message CLOB,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT,
    entity_type VARCHAR(20),
    entity_id BIGINT,
    operation VARCHAR(50),
    old_value CLOB,
    new_value CLOB,
    operator VARCHAR(50),
    ip_address VARCHAR(50),
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 费用统计表
CREATE TABLE IF NOT EXISTS cost_statistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT,
    api_type VARCHAR(50),
    call_count INT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    estimated_cost DECIMAL(10,4) DEFAULT 0,
    created_date DATE,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ==================== 阶段二：剧集制作相关表 ====================

-- 剧集表
CREATE TABLE IF NOT EXISTS episode (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    episode_number INT NOT NULL,
    episode_name VARCHAR(100),
    script_text CLOB,
    parsed_script CLOB,
    total_shots INT DEFAULT 0,
    total_duration INT DEFAULT 0,
    status INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

-- 场景表
CREATE TABLE IF NOT EXISTS scene (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    scene_name VARCHAR(100) NOT NULL,
    scene_code VARCHAR(20),
    description CLOB,
    location_type VARCHAR(50),
    time_of_day VARCHAR(20),
    weather VARCHAR(20),
    custom_prompt CLOB,
    style_keywords VARCHAR(500),
    aspect_ratio VARCHAR(10),
    quality VARCHAR(20),
    status INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

-- 道具表
CREATE TABLE IF NOT EXISTS prop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    prop_name VARCHAR(100) NOT NULL,
    prop_code VARCHAR(20),
    description CLOB,
    prop_type VARCHAR(50),
    color VARCHAR(50),
    size VARCHAR(20),
    custom_prompt CLOB,
    style_keywords VARCHAR(500),
    aspect_ratio VARCHAR(10),
    quality VARCHAR(20),
    status INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

-- 分镜表
CREATE TABLE IF NOT EXISTS shot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    episode_id BIGINT NOT NULL,
    shot_number INT NOT NULL,
    shot_name VARCHAR(100),
    scene_id BIGINT,
    description CLOB,
    camera_angle VARCHAR(50),
    camera_movement VARCHAR(50),
    shot_type VARCHAR(100),
    start_time INT DEFAULT 0,
    end_time INT DEFAULT 5,
    duration INT DEFAULT 5,
    sound_effect VARCHAR(500),
    characters_json CLOB,
    props_json CLOB,
    reference_prompt CLOB,
    user_prompt CLOB,
    video_url VARCHAR(500),
    thumbnail_url VARCHAR(500),
    video_seed BIGINT,
    generation_status INT DEFAULT 0,
    generation_error VARCHAR(500),
    generation_duration INT,
    generation_start_time TIMESTAMP,
    status INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

-- 添加 shot_name 列（如果不存在）
ALTER TABLE shot ADD COLUMN IF NOT EXISTS shot_name VARCHAR(100);

-- 添加视频分辨率和比例字段
ALTER TABLE shot ADD COLUMN IF NOT EXISTS resolution VARCHAR(10) DEFAULT '720p';
ALTER TABLE shot ADD COLUMN IF NOT EXISTS aspect_ratio VARCHAR(10) DEFAULT '16:9';

-- 分镜-角色关联表
CREATE TABLE IF NOT EXISTS shot_character (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    character_action CLOB,
    character_expression VARCHAR(100),
    clothing_id INT,
    position_x DECIMAL(5,2),
    position_y DECIMAL(5,2),
    scale DECIMAL(5,2) DEFAULT 1.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 分镜-道具关联表
CREATE TABLE IF NOT EXISTS shot_prop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    prop_id BIGINT NOT NULL,
    position_x DECIMAL(5,2),
    position_y DECIMAL(5,2),
    scale DECIMAL(5,2) DEFAULT 1.0,
    rotation DECIMAL(5,2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 场景资产表
CREATE TABLE IF NOT EXISTS scene_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scene_id BIGINT NOT NULL,
    asset_type VARCHAR(20),
    view_type VARCHAR(20),
    version INT DEFAULT 1,
    file_path VARCHAR(500),
    thumbnail_path VARCHAR(500),
    file_name VARCHAR(200),
    status INT DEFAULT 0,
    is_active INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 道具资产表
CREATE TABLE IF NOT EXISTS prop_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prop_id BIGINT NOT NULL,
    episode_id BIGINT,
    asset_type VARCHAR(20),
    view_type VARCHAR(20),
    version INT DEFAULT 1,
    file_path VARCHAR(500),
    transparent_path VARCHAR(500),
    thumbnail_path VARCHAR(500),
    file_name VARCHAR(200),
    status INT DEFAULT 0,
    is_active INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_prop_asset_episode_id ON prop_asset(episode_id);

-- 场景资产元数据表
CREATE TABLE IF NOT EXISTS scene_asset_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL UNIQUE,
    prompt CLOB,
    user_prompt CLOB,
    negative_prompt CLOB,
    seed BIGINT,
    model_version VARCHAR(50) DEFAULT 'seedream-5.0-lite',
    image_width INT,
    image_height INT,
    aspect_ratio VARCHAR(10),
    generation_time_ms BIGINT,
    api_response CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 道具资产元数据表
CREATE TABLE IF NOT EXISTS prop_asset_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL UNIQUE,
    prompt CLOB,
    user_prompt CLOB,
    negative_prompt CLOB,
    seed BIGINT,
    model_version VARCHAR(50) DEFAULT 'seedream-5.0-lite',
    image_width INT,
    image_height INT,
    aspect_ratio VARCHAR(10),
    generation_time_ms BIGINT,
    api_response CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 视频资产元数据表
CREATE TABLE IF NOT EXISTS video_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL UNIQUE,
    prompt CLOB,
    user_prompt CLOB,
    negative_prompt CLOB,
    seed BIGINT,
    model_version VARCHAR(50) DEFAULT 'seedance-2.0',
    video_duration INT,
    video_width INT,
    video_height INT,
    generation_time_ms BIGINT,
    api_response CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 分镜参考图表
CREATE TABLE IF NOT EXISTS shot_reference_image (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    image_type VARCHAR(20) NOT NULL,
    reference_id BIGINT,
    reference_name VARCHAR(100),
    image_url VARCHAR(500) NOT NULL,
    display_order INT DEFAULT 0,
    is_user_added TINYINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 分镜视频资产版本表
CREATE TABLE IF NOT EXISTS shot_video_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    video_url VARCHAR(1024),
    thumbnail_url VARCHAR(1024),
    is_active TINYINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_shot_video_asset_shot_id ON shot_video_asset(shot_id);
CREATE INDEX IF NOT EXISTS idx_shot_video_asset_version ON shot_video_asset(shot_id, version);

-- 分镜视频资产元数据表
CREATE TABLE IF NOT EXISTS shot_video_asset_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_video_asset_id BIGINT NOT NULL UNIQUE,
    model VARCHAR(100),
    prompt CLOB,
    reference_urls CLOB,
    generation_params CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_shot_video_asset_metadata_asset_id ON shot_video_asset_metadata(shot_video_asset_id);

CREATE INDEX IF NOT EXISTS idx_shot_ref_shot_id ON shot_reference_image(shot_id);
CREATE INDEX IF NOT EXISTS idx_shot_ref_reference ON shot_reference_image(image_type, reference_id);

-- 主体替换任务表
CREATE TABLE IF NOT EXISTS subject_replacement_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_name VARCHAR(100),
    original_video_url VARCHAR(1024) NOT NULL,
    output_video_url VARCHAR(1024),
    thumbnail_url VARCHAR(1024),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    aspect_ratio VARCHAR(10) DEFAULT '16:9',
    duration INT DEFAULT 5,
    generate_audio BOOLEAN DEFAULT TRUE,
    watermark BOOLEAN DEFAULT FALSE,
    model VARCHAR(100),
    prompt CLOB,
    replacements_json CLOB,
    volcengine_task_id VARCHAR(100),
    error_message CLOB,
    submitted_at TIMESTAMP,
    completed_at TIMESTAMP,
    generation_duration INT,
    seed BIGINT,
    deducted_credits INT DEFAULT NULL,
    credits_refunded BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_subject_replacement_user_created ON subject_replacement_task(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_subject_replacement_status ON subject_replacement_task(status);

-- ==================== 分镜表补充字段 ====================
-- 添加缺失的分镜字段
ALTER TABLE shot ADD COLUMN IF NOT EXISTS shot_type VARCHAR(50);
ALTER TABLE shot ADD COLUMN IF NOT EXISTS start_time INT;
ALTER TABLE shot ADD COLUMN IF NOT EXISTS end_time INT;
ALTER TABLE shot ADD COLUMN IF NOT EXISTS sound_effect CLOB;
ALTER TABLE shot ADD COLUMN IF NOT EXISTS scene_name VARCHAR(100);
