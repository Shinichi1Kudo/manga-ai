-- ============================================
-- AI 智能漫剧制作系统 - 数据库初始化脚本
-- 数据库: MySQL 8.0+
-- 字符集: utf8mb4
-- ============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS manga_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE manga_ai;

-- ============================================
-- 1. 系列表 (series)
-- ============================================
DROP TABLE IF EXISTS series;
CREATE TABLE series (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    series_name VARCHAR(100) NOT NULL COMMENT '系列名称',
    outline TEXT COMMENT '剧本大纲',
    background TEXT COMMENT '背景设定/世界观',
    character_intro TEXT COMMENT '人物介绍原文',
    style_keywords VARCHAR(500) COMMENT '风格关键词',
    color_preference VARCHAR(200) COMMENT '色调偏好',
    art_style_ref VARCHAR(500) COMMENT '美术风格参考',
    status TINYINT DEFAULT 0 COMMENT '状态: 0-初始化中 1-待审核 2-已锁定',
    project_path VARCHAR(500) COMMENT '项目目录路径',
    global_seed BIGINT COMMENT '全局风格Seed',
    global_style_prompt TEXT COMMENT '全局风格Prompt',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by VARCHAR(50) COMMENT '创建人',
    is_deleted TINYINT DEFAULT 0 COMMENT '是否删除: 0-否 1-是',
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='剧集系列表';

-- ============================================
-- 2. 角色表 (role)
-- ============================================
DROP TABLE IF EXISTS role;
CREATE TABLE role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    series_id BIGINT NOT NULL COMMENT '系列ID',
    role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(20) COMMENT '角色编码(如ROLE_001)',
    status TINYINT DEFAULT 0 COMMENT '状态: 0-提取中 1-待审核 2-已确认 3-已锁定',
    age VARCHAR(20) COMMENT '年龄',
    gender VARCHAR(10) COMMENT '性别',
    appearance TEXT COMMENT '外貌描述',
    personality TEXT COMMENT '性格描述',
    clothing TEXT COMMENT '服装描述',
    special_marks TEXT COMMENT '特殊标识',
    original_text TEXT COMMENT '原始人物介绍片段',
    extract_confidence DECIMAL(3,2) COMMENT 'NLP提取置信度(0-1)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT DEFAULT 0 COMMENT '是否删除: 0-否 1-是',
    INDEX idx_series_id (series_id),
    INDEX idx_status (status),
    INDEX idx_role_code (role_code),
    FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- ============================================
-- 3. 角色属性表 (role_attribute)
-- ============================================
DROP TABLE IF EXISTS role_attribute;
CREATE TABLE role_attribute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    attr_key VARCHAR(50) NOT NULL COMMENT '属性键(发型/肤色/体型/身高/发色/眼睛等)',
    attr_value TEXT COMMENT '属性值',
    confidence DECIMAL(3,2) COMMENT '提取置信度',
    source_type VARCHAR(20) DEFAULT 'NLP_EXTRACT' COMMENT '来源类型: NLP_EXTRACT-自动提取 MANUAL_INPUT-手动输入',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_role_id (role_id),
    INDEX idx_attr_key (attr_key),
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色属性表';

-- ============================================
-- 4. 角色资产表 (role_asset)
-- ============================================
DROP TABLE IF EXISTS role_asset;
CREATE TABLE role_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    asset_type VARCHAR(20) NOT NULL COMMENT '资产类型: MULTI_VIEW-多视图 SINGLE_VIEW-单视图 EXPRESSION-表情 COSTUME-服装',
    view_type VARCHAR(20) COMMENT '视图类型: FRONT-正面 SIDE-侧面 BACK-背面 THREE_QUARTER-四分之三',
    clothing_id INT DEFAULT 1 COMMENT '服装编号',
    version INT DEFAULT 1 COMMENT '版本号',
    file_path VARCHAR(500) COMMENT '原始图片路径',
    transparent_path VARCHAR(500) COMMENT '透明PNG路径',
    thumbnail_path VARCHAR(500) COMMENT '缩略图路径',
    file_name VARCHAR(200) COMMENT '文件名',
    status TINYINT DEFAULT 0 COMMENT '状态: 0-生成中 1-待审核 2-已确认 3-已锁定',
    is_active TINYINT DEFAULT 1 COMMENT '是否当前激活版本: 0-否 1-是',
    validation_passed TINYINT DEFAULT 0 COMMENT '规范校验是否通过: 0-否 1-是',
    validation_result TEXT COMMENT '规范校验结果JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_role_id (role_id),
    INDEX idx_view_type (view_type),
    INDEX idx_status (status),
    INDEX idx_active (is_active),
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色资产表';

-- ============================================
-- 5. 资产元数据表 (asset_metadata)
-- ============================================
DROP TABLE IF EXISTS asset_metadata;
CREATE TABLE asset_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    asset_id BIGINT NOT NULL UNIQUE COMMENT '资产ID',
    prompt TEXT COMMENT '生成用Prompt',
    negative_prompt TEXT COMMENT '负向Prompt',
    seed BIGINT COMMENT 'Seed值',
    model_version VARCHAR(50) DEFAULT 'seedream-5.0-lite' COMMENT '模型版本',
    image_width INT COMMENT '图片宽度',
    image_height INT COMMENT '图片高度',
    aspect_ratio VARCHAR(10) COMMENT '宽高比: 16:9 / 3:4',
    generation_time_ms BIGINT COMMENT '生成耗时(毫秒)',
    api_response TEXT COMMENT 'API原始响应JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (asset_id) REFERENCES role_asset(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产元数据表';

-- ============================================
-- 6. 异步任务队列表 (task_queue)
-- ============================================
DROP TABLE IF EXISTS task_queue;
CREATE TABLE task_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_type VARCHAR(50) NOT NULL COMMENT '任务类型: ROLE_EXTRACT-角色提取 IMAGE_GENERATE-图片生成 BG_REMOVE-背景移除',
    ref_entity_type VARCHAR(20) COMMENT '关联实体类型: SERIES/ROLE/ASSET',
    ref_entity_id BIGINT COMMENT '关联实体ID',
    status TINYINT DEFAULT 0 COMMENT '状态: 0-待处理 1-处理中 2-成功 3-失败',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    max_retry INT DEFAULT 3 COMMENT '最大重试次数',
    priority INT DEFAULT 0 COMMENT '优先级(数字越大优先级越高)',
    input_data TEXT COMMENT '输入参数JSON',
    output_data TEXT COMMENT '输出结果JSON',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME COMMENT '开始处理时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_status (status),
    INDEX idx_task_type (task_type),
    INDEX idx_ref (ref_entity_type, ref_entity_id),
    INDEX idx_priority (priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步任务队列表';

-- ============================================
-- 7. 操作日志表 (operation_log)
-- ============================================
DROP TABLE IF EXISTS operation_log;
CREATE TABLE operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    series_id BIGINT COMMENT '系列ID',
    entity_type VARCHAR(20) COMMENT '实体类型: SERIES/ROLE/ASSET',
    entity_id BIGINT COMMENT '实体ID',
    operation VARCHAR(50) NOT NULL COMMENT '操作类型: CREATE/UPDATE/DELETE/LOCK/CONFIRM/REGENERATE等',
    old_value TEXT COMMENT '旧值JSON',
    new_value TEXT COMMENT '新值JSON',
    operator VARCHAR(50) COMMENT '操作人',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    remark VARCHAR(500) COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_series_id (series_id),
    INDEX idx_entity (entity_type, entity_id),
    INDEX idx_operation (operation),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- ============================================
-- 8. 费用统计表 (cost_statistics)
-- ============================================
DROP TABLE IF EXISTS cost_statistics;
CREATE TABLE cost_statistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    series_id BIGINT COMMENT '系列ID',
    api_type VARCHAR(50) NOT NULL COMMENT 'API类型: SEEDREAM/NLP等',
    call_count INT DEFAULT 0 COMMENT '调用次数',
    total_tokens BIGINT DEFAULT 0 COMMENT '总Token数',
    estimated_cost DECIMAL(10,4) DEFAULT 0 COMMENT '预估费用',
    created_date DATE NOT NULL COMMENT '统计日期',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_series_id (series_id),
    INDEX idx_created_date (created_date),
    UNIQUE KEY uk_series_api_date (series_id, api_type, created_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='费用统计表';

-- ============================================
-- 初始数据
-- ============================================

-- 插入默认系统配置
-- 可以根据需要添加系统级配置数据

-- ============================================
-- 视图定义 (可选)
-- ============================================

-- 角色资产详情视图
CREATE OR REPLACE VIEW v_role_asset_detail AS
SELECT
    ra.id AS asset_id,
    ra.role_id,
    r.role_name,
    r.series_id,
    s.series_name,
    ra.asset_type,
    ra.view_type,
    ra.clothing_id,
    ra.version,
    ra.file_path,
    ra.transparent_path,
    ra.thumbnail_path,
    ra.status,
    ra.is_active,
    ra.validation_passed,
    am.prompt,
    am.seed,
    am.model_version,
    am.aspect_ratio
FROM role_asset ra
LEFT JOIN role r ON ra.role_id = r.id
LEFT JOIN series s ON r.series_id = s.id
LEFT JOIN asset_metadata am ON ra.id = am.asset_id;

-- 系列进度视图
CREATE OR REPLACE VIEW v_series_progress AS
SELECT
    s.id AS series_id,
    s.series_name,
    s.status AS series_status,
    COUNT(DISTINCT r.id) AS total_roles,
    SUM(CASE WHEN r.status >= 2 THEN 1 ELSE 0 END) AS confirmed_roles,
    COUNT(DISTINCT ra.id) AS total_assets,
    SUM(CASE WHEN ra.status >= 2 THEN 1 ELSE 0 END) AS confirmed_assets,
    SUM(CASE WHEN tq.status = 0 THEN 1 ELSE 0 END) AS pending_tasks,
    SUM(CASE WHEN tq.status = 1 THEN 1 ELSE 0 END) AS running_tasks,
    SUM(CASE WHEN tq.status = 3 THEN 1 ELSE 0 END) AS failed_tasks
FROM series s
LEFT JOIN role r ON s.id = r.series_id AND r.is_deleted = 0
LEFT JOIN role_asset ra ON r.id = ra.role_id
LEFT JOIN task_queue tq ON s.id = tq.ref_entity_id AND tq.ref_entity_type = 'SERIES'
WHERE s.is_deleted = 0
GROUP BY s.id, s.series_name, s.status;

-- ============================================
-- 存储过程 (可选)
-- ============================================

DELIMITER //

-- 锁定系列存储过程
CREATE PROCEDURE sp_lock_series(IN p_series_id BIGINT)
BEGIN
    DECLARE v_status TINYINT;
    DECLARE v_unconfirmed_count INT;

    -- 检查系列状态
    SELECT status INTO v_status FROM series WHERE id = p_series_id;
    IF v_status = 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '系列已锁定';
    END IF;

    -- 检查是否有未确认的角色
    SELECT COUNT(*) INTO v_unconfirmed_count
    FROM role
    WHERE series_id = p_series_id AND status < 2 AND is_deleted = 0;

    IF v_unconfirmed_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = CONCAT('存在 ', v_unconfirmed_count, ' 个未确认的角色');
    END IF;

    -- 锁定所有资产
    UPDATE role_asset ra
    INNER JOIN role r ON ra.role_id = r.id
    SET ra.status = 3
    WHERE r.series_id = p_series_id AND ra.status = 2;

    -- 锁定所有角色
    UPDATE role SET status = 3 WHERE series_id = p_series_id AND status = 2;

    -- 锁定系列
    UPDATE series SET status = 2 WHERE id = p_series_id;
END //

DELIMITER ;

-- ============================================
-- 完成
-- ============================================
SELECT 'Database initialization completed!' AS message;
