ALTER TABLE prop_asset ADD COLUMN episode_id BIGINT NULL COMMENT '生成来源剧集ID';
CREATE INDEX idx_prop_asset_episode_id ON prop_asset (episode_id);
