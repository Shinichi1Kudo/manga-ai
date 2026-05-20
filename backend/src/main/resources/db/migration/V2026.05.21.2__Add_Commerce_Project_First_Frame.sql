ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS try_on_first_frame_enabled TINYINT(1) DEFAULT 0 COMMENT '是否生成试穿首帧确认图';
ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS try_on_first_frame_aspect_ratio VARCHAR(10) DEFAULT '9:16' COMMENT '试穿首帧图片比例';
ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS try_on_first_frame_resolution VARCHAR(10) DEFAULT '2K' COMMENT '试穿首帧分辨率';
ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS try_on_first_frame_url VARCHAR(1024) DEFAULT NULL COMMENT '试穿首帧确认图URL';
ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS try_on_first_frame_status VARCHAR(30) DEFAULT NULL COMMENT '试穿首帧生成状态';
ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS try_on_first_frame_error TEXT COMMENT '试穿首帧生成错误';
ALTER TABLE commerce_project ADD COLUMN IF NOT EXISTS try_on_first_frame_confirmed TINYINT(1) DEFAULT 0 COMMENT '试穿首帧是否已确认';
