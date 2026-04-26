-- 分镜视频资产版本表
CREATE TABLE IF NOT EXISTS shot_video_asset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shot_id BIGINT NOT NULL COMMENT '分镜ID',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    video_url VARCHAR(1024) COMMENT '视频URL',
    thumbnail_url VARCHAR(1024) COMMENT '缩略图URL',
    is_active TINYINT DEFAULT 0 COMMENT '是否为当前激活版本(0-否,1-是)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shot_id (shot_id),
    INDEX idx_shot_version (shot_id, version)
) COMMENT='分镜视频资产版本表';

-- 分镜视频资产元数据表
CREATE TABLE IF NOT EXISTS shot_video_asset_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shot_video_asset_id BIGINT NOT NULL COMMENT '视频资产ID',
    model VARCHAR(100) COMMENT '生成模型',
    prompt TEXT COMMENT '生成提示词',
    reference_urls TEXT COMMENT '参考图URLs(JSON数组)',
    generation_params TEXT COMMENT '其他生成参数(JSON)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_video_asset_id (shot_video_asset_id)
) COMMENT='分镜视频资产元数据表';
