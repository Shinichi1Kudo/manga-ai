ALTER TABLE role_asset ADD COLUMN clothing_prompt TEXT DEFAULT NULL COMMENT '服装专属提示词';

-- 迁移现有数据：从 asset_metadata 回填
UPDATE role_asset ra
INNER JOIN asset_metadata am ON ra.id = am.asset_id
SET ra.clothing_prompt = am.user_prompt
WHERE ra.clothing_prompt IS NULL AND am.user_prompt IS NOT NULL;