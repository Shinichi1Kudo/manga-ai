CREATE INDEX idx_shot_episode_detail_list
    ON shot (episode_id, is_deleted, status, shot_number);

CREATE INDEX idx_shot_character_shot_role
    ON shot_character (shot_id, role_id);

CREATE INDEX idx_shot_prop_shot_prop
    ON shot_prop (shot_id, prop_id);

CREATE INDEX idx_scene_asset_scene_active
    ON scene_asset (scene_id, is_active);

CREATE INDEX idx_role_asset_role_clothing_active
    ON role_asset (role_id, clothing_id, is_active);

CREATE INDEX idx_prop_asset_prop_active_episode_version
    ON prop_asset (prop_id, is_active, episode_id, version);

CREATE INDEX idx_shot_video_asset_shot_active_version
    ON shot_video_asset (shot_id, is_active, version);
