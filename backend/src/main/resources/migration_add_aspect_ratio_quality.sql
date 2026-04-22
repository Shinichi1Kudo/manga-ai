-- Migration: Add aspect_ratio and quality columns to scene and prop tables
-- Date: 2026-04-22

-- Add columns to scene table
ALTER TABLE scene ADD COLUMN IF NOT EXISTS aspect_ratio VARCHAR(10) COMMENT '图片比例';
ALTER TABLE scene ADD COLUMN IF NOT EXISTS quality VARCHAR(20) COMMENT '清晰度';

-- Add columns to prop table
ALTER TABLE prop ADD COLUMN IF NOT EXISTS aspect_ratio VARCHAR(10) COMMENT '图片比例';
ALTER TABLE prop ADD COLUMN IF NOT EXISTS quality VARCHAR(20) COMMENT '清晰度';
