-- H2 数据库初始化脚本

-- 系列表
CREATE TABLE IF NOT EXISTS series (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50),
    is_deleted INT DEFAULT 0
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
    original_text CLOB,
    extract_confidence DECIMAL(3,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

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
