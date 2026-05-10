-- MySQL 剧集制作相关表 (阶段二)

-- 剧集表
CREATE TABLE IF NOT EXISTS episode (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    episode_number INT NOT NULL,
    episode_name VARCHAR(100),
    script_text LONGTEXT,
    parsed_script LONGTEXT,
    total_shots INT DEFAULT 0,
    total_duration INT DEFAULT 0,
    status INT DEFAULT 0 COMMENT '0-待解析 1-解析中 2-待审核 3-制作中 4-已完成',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0,
    INDEX idx_series_id (series_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='剧集表';

-- 场景表
CREATE TABLE IF NOT EXISTS scene (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    scene_name VARCHAR(100) NOT NULL,
    scene_code VARCHAR(20),
    description LONGTEXT,
    location_type VARCHAR(50),
    time_of_day VARCHAR(20),
    weather VARCHAR(20),
    custom_prompt LONGTEXT,
    style_keywords VARCHAR(500),
    aspect_ratio VARCHAR(10) COMMENT '图片比例',
    quality VARCHAR(20) COMMENT '清晰度',
    status INT DEFAULT 0 COMMENT '0-待生成 1-生成中 2-已完成 3-生成失败',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0,
    INDEX idx_series_id (series_id),
    INDEX idx_scene_code (scene_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景表';

-- 道具表
CREATE TABLE IF NOT EXISTS prop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL,
    prop_name VARCHAR(100) NOT NULL,
    prop_code VARCHAR(20),
    description LONGTEXT,
    prop_type VARCHAR(50),
    color VARCHAR(50),
    size VARCHAR(20),
    custom_prompt LONGTEXT,
    style_keywords VARCHAR(500),
    aspect_ratio VARCHAR(10) COMMENT '图片比例',
    quality VARCHAR(20) COMMENT '清晰度',
    status INT DEFAULT 0 COMMENT '0-待生成 1-生成中 2-已完成 3-生成失败',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0,
    INDEX idx_series_id (series_id),
    INDEX idx_prop_code (prop_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='道具表';

-- 分镜表
CREATE TABLE IF NOT EXISTS shot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    episode_id BIGINT NOT NULL,
    shot_number INT NOT NULL,
    scene_id BIGINT,
    description LONGTEXT,
    camera_angle VARCHAR(50),
    camera_movement VARCHAR(50),
    duration INT DEFAULT 5,
    characters_json LONGTEXT,
    props_json LONGTEXT,
    reference_prompt LONGTEXT,
    user_prompt LONGTEXT,
    video_url VARCHAR(500),
    thumbnail_url VARCHAR(500),
    video_seed BIGINT,
    generation_status INT DEFAULT 0 COMMENT '0-待生成 1-生成中 2-已完成 3-生成失败',
    status INT DEFAULT 0 COMMENT '0-待审核 1-已通过 2-已拒绝',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0,
    INDEX idx_episode_id (episode_id),
    INDEX idx_scene_id (scene_id),
    INDEX idx_generation_status (generation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分镜表';

-- 分镜-角色关联表
CREATE TABLE IF NOT EXISTS shot_character (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    character_action LONGTEXT,
    character_expression VARCHAR(100),
    clothing_id INT,
    position_x DECIMAL(5,2),
    position_y DECIMAL(5,2),
    scale DECIMAL(5,2) DEFAULT 1.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shot_id (shot_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分镜-角色关联表';

-- 分镜-道具关联表
CREATE TABLE IF NOT EXISTS shot_prop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    prop_id BIGINT NOT NULL,
    position_x DECIMAL(5,2),
    position_y DECIMAL(5,2),
    scale DECIMAL(5,2) DEFAULT 1.0,
    rotation DECIMAL(5,2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shot_id (shot_id),
    INDEX idx_prop_id (prop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分镜-道具关联表';

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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_scene_id (scene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景资产表';

-- 道具资产表
CREATE TABLE IF NOT EXISTS prop_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prop_id BIGINT NOT NULL,
    episode_id BIGINT COMMENT '生成来源剧集ID',
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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_prop_id (prop_id),
    INDEX idx_prop_asset_episode_id (episode_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='道具资产表';

-- 场景资产元数据表
CREATE TABLE IF NOT EXISTS scene_asset_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL UNIQUE,
    prompt LONGTEXT,
    user_prompt LONGTEXT,
    negative_prompt LONGTEXT,
    seed BIGINT,
    model_version VARCHAR(50) DEFAULT 'seedream-5.0-lite',
    image_width INT,
    image_height INT,
    aspect_ratio VARCHAR(10),
    generation_time_ms BIGINT,
    api_response LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_asset_id (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景资产元数据表';

-- 道具资产元数据表
CREATE TABLE IF NOT EXISTS prop_asset_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL UNIQUE,
    prompt LONGTEXT,
    user_prompt LONGTEXT,
    negative_prompt LONGTEXT,
    seed BIGINT,
    model_version VARCHAR(50) DEFAULT 'seedream-5.0-lite',
    image_width INT,
    image_height INT,
    aspect_ratio VARCHAR(10),
    generation_time_ms BIGINT,
    api_response LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_asset_id (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='道具资产元数据表';

-- 视频资产元数据表
CREATE TABLE IF NOT EXISTS video_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shot_id BIGINT NOT NULL UNIQUE,
    prompt LONGTEXT,
    user_prompt LONGTEXT,
    negative_prompt LONGTEXT,
    seed BIGINT,
    model_version VARCHAR(50) DEFAULT 'seedance-2.0',
    video_duration INT,
    video_width INT,
    video_height INT,
    generation_time_ms BIGINT,
    api_response LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shot_id (shot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频资产元数据表';
