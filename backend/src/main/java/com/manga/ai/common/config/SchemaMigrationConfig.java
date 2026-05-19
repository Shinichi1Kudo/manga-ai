package com.manga.ai.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Small idempotent schema fixes for local/dev deployments that do not run migrations automatically.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SchemaMigrationConfig {

    private final DataSource dataSource;

    @Bean
    public ApplicationRunner propAssetEpisodeIdMigrationRunner() {
        return args -> {
            ensurePropAssetEpisodeIdColumn();
            ensureCommerceTables();
            ensureSubjectReplacementTaskTable();
            ensureGptImage2TaskTable();
            ensurePerformanceIndexes();
        };
    }

    private void ensurePropAssetEpisodeIdColumn() {
        try (Connection connection = dataSource.getConnection()) {
            if (!hasTable(connection, "prop_asset")) {
                log.debug("跳过数据库迁移: prop_asset 表不存在");
                return;
            }
            if (hasColumn(connection, "prop_asset", "episode_id")) {
                ensureIndex(connection);
                return;
            }

            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            try (Statement statement = connection.createStatement()) {
                statement.execute(mysql
                        ? "ALTER TABLE prop_asset ADD COLUMN episode_id BIGINT NULL COMMENT '生成来源剧集ID'"
                        : "ALTER TABLE prop_asset ADD COLUMN episode_id BIGINT");
                ensureIndex(connection);
                log.info("数据库迁移完成: prop_asset.episode_id");
            }
        } catch (Exception e) {
            log.error("数据库迁移失败: prop_asset.episode_id", e);
            throw new IllegalStateException("数据库迁移失败: prop_asset.episode_id", e);
        }
    }

    private boolean hasTable(Connection connection, String tableName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName, null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName.toUpperCase(), null)) {
            return tables.next();
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return columns.next();
        }
    }

    private void ensureIndex(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            statement.execute(mysql
                    ? "CREATE INDEX idx_prop_asset_episode_id ON prop_asset (episode_id)"
                    : "CREATE INDEX IF NOT EXISTS idx_prop_asset_episode_id ON prop_asset (episode_id)");
        } catch (Exception e) {
            log.debug("prop_asset.episode_id 索引已存在或当前数据库不支持重复创建: {}", e.getMessage());
        }
    }

    private void ensurePerformanceIndexes() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            ensureEpisodeDetailShotColumns(connection, mysql);
            ensureRoleCreationSettingColumns(connection, mysql);
            ensureIndex(connection, "idx_series_user_deleted_created", "series", "user_id, is_deleted, created_at");
            ensureIndex(connection, "idx_role_series_deleted", "role", "series_id, is_deleted");
            ensureIndex(connection, "idx_shot_episode_detail_list", "shot", "episode_id, is_deleted, status, shot_number");
            ensureIndex(connection, "idx_shot_character_shot_role", "shot_character", "shot_id, role_id");
            ensureIndex(connection, "idx_shot_prop_shot_prop", "shot_prop", "shot_id, prop_id");
            ensureIndex(connection, "idx_scene_asset_scene_active", "scene_asset", "scene_id, is_active");
            ensureIndex(connection, "idx_role_asset_role_clothing_active", "role_asset", "role_id, clothing_id, is_active");
            ensureIndex(connection, "idx_prop_asset_prop_active_episode_version", "prop_asset", "prop_id, is_active, episode_id, version");
            ensureIndex(connection, "idx_shot_video_asset_shot_active_version", "shot_video_asset", "shot_id, is_active, version");
        } catch (Exception e) {
            log.warn("性能索引检查失败: {}", e.getMessage());
        }
    }

    private void ensureEpisodeDetailShotColumns(Connection connection, boolean mysql) throws Exception {
        ensureColumn(connection, mysql, "shot", "shot_name",
                "VARCHAR(100) DEFAULT NULL COMMENT '分镜名称'",
                "VARCHAR(100)");
        ensureColumn(connection, mysql, "shot", "scene_name",
                "VARCHAR(100) DEFAULT NULL COMMENT '场景名称'",
                "VARCHAR(100)");
        ensureColumn(connection, mysql, "shot", "shot_type",
                "VARCHAR(100) DEFAULT NULL COMMENT '镜头类型'",
                "VARCHAR(100)");
        ensureColumn(connection, mysql, "shot", "start_time",
                "INT DEFAULT 0 COMMENT '开始时间（秒）'",
                "INT DEFAULT 0");
        ensureColumn(connection, mysql, "shot", "end_time",
                "INT DEFAULT 5 COMMENT '结束时间（秒）'",
                "INT DEFAULT 5");
        ensureColumn(connection, mysql, "shot", "resolution",
                "VARCHAR(10) DEFAULT '720p' COMMENT '视频分辨率'",
                "VARCHAR(10) DEFAULT '720p'");
        ensureColumn(connection, mysql, "shot", "aspect_ratio",
                "VARCHAR(10) DEFAULT '16:9' COMMENT '视频比例'",
                "VARCHAR(10) DEFAULT '16:9'");
        ensureColumn(connection, mysql, "shot", "sound_effect",
                "TEXT COMMENT '音效描述'",
                "CLOB");
        ensureColumn(connection, mysql, "shot", "generation_error",
                "VARCHAR(500) DEFAULT NULL COMMENT '生成失败原因'",
                "VARCHAR(500)");
        ensureColumn(connection, mysql, "shot", "generation_duration",
                "INT DEFAULT NULL COMMENT '生成耗时（秒）'",
                "INT");
        ensureColumn(connection, mysql, "shot", "generation_start_time",
                "TIMESTAMP NULL COMMENT '生成开始时间'",
                "TIMESTAMP");
        ensureColumn(connection, mysql, "shot", "deducted_credits",
                "INT DEFAULT NULL COMMENT '视频生成已扣除积分（用于失败返还）'",
                "INT DEFAULT NULL");
        ensureColumn(connection, mysql, "shot", "video_model",
                "VARCHAR(100) DEFAULT 'seedance-2.0-fast' COMMENT '视频生成模型'",
                "VARCHAR(100) DEFAULT 'seedance-2.0-fast'");
        ensureColumn(connection, mysql, "shot", "description_edited",
                "TINYINT(1) DEFAULT 0 COMMENT '剧情是否用户编辑过'",
                "BOOLEAN DEFAULT FALSE");
        ensureColumn(connection, mysql, "shot", "scene_edited",
                "TINYINT(1) DEFAULT 0 COMMENT '场景是否用户编辑过'",
                "BOOLEAN DEFAULT FALSE");
    }

    private void ensureRoleCreationSettingColumns(Connection connection, boolean mysql) throws Exception {
        ensureColumn(connection, mysql, "role", "style_key",
                "VARCHAR(50) DEFAULT NULL COMMENT '创建角色时选择的前端风格Key'",
                "VARCHAR(50)");
        ensureColumn(connection, mysql, "role", "aspect_ratio",
                "VARCHAR(10) DEFAULT NULL COMMENT '创建角色时选择的人物图片比例'",
                "VARCHAR(10)");
        ensureColumn(connection, mysql, "role", "quality",
                "VARCHAR(20) DEFAULT NULL COMMENT '创建角色时选择的清晰度'",
                "VARCHAR(20)");
        ensureColumn(connection, mysql, "role", "detailed_view",
                "TINYINT(1) DEFAULT 0 COMMENT '创建角色时是否启用精细三视图'",
                "BOOLEAN DEFAULT FALSE");
        ensureColumn(connection, mysql, "role", "face_closeup_view",
                "TINYINT(1) DEFAULT 0 COMMENT '创建角色时是否启用大头特写三视图'",
                "BOOLEAN DEFAULT FALSE");
    }

    private void ensureSubjectReplacementTaskTable() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            try (Statement statement = connection.createStatement()) {
                statement.execute(mysql ? mysqlSubjectReplacementTaskSql() : genericSubjectReplacementTaskSql());
            }
            ensureSubjectReplacementCreditColumns(connection, mysql);
            ensureIndex(connection, "idx_subject_replacement_user_created", "subject_replacement_task", "user_id, created_at");
            ensureIndex(connection, "idx_subject_replacement_status", "subject_replacement_task", "status");
        } catch (Exception e) {
            log.error("数据库迁移失败: subject_replacement_task", e);
            throw new IllegalStateException("数据库迁移失败: subject_replacement_task", e);
        }
    }

    private void ensureCommerceTables() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            try (Statement statement = connection.createStatement()) {
                statement.execute(mysql ? mysqlCommerceSeriesSql() : genericCommerceSeriesSql());
                statement.execute(mysql ? mysqlCommerceModelSql() : genericCommerceModelSql());
                statement.execute(mysql ? mysqlCommerceProductSql() : genericCommerceProductSql());
                statement.execute(mysql ? mysqlCommerceProjectSql() : genericCommerceProjectSql());
                statement.execute(mysql ? mysqlCommerceShotSql() : genericCommerceShotSql());
            }
            ensureIndex(connection, "idx_commerce_series_user_created", "commerce_series", "user_id, created_at");
            ensureIndex(connection, "idx_commerce_series_user_status", "commerce_series", "user_id, status");
            ensureIndex(connection, "idx_commerce_model_series_created", "commerce_model", "series_id, created_at");
            ensureIndex(connection, "idx_commerce_model_user_status", "commerce_model", "user_id, status");
            ensureIndex(connection, "idx_commerce_product_model_created", "commerce_product", "model_id, created_at");
            ensureIndex(connection, "idx_commerce_product_series_created", "commerce_product", "series_id, created_at");
            ensureIndex(connection, "idx_commerce_product_user_status", "commerce_product", "user_id, status");
            ensureIndex(connection, "idx_commerce_project_product_created", "commerce_project", "product_id, created_at");
            ensureIndex(connection, "idx_commerce_project_user_status", "commerce_project", "user_id, status");
            ensureIndex(connection, "idx_commerce_shot_project_order", "commerce_shot", "project_id, sort_order");
            ensureIndex(connection, "idx_commerce_shot_generation_status", "commerce_shot", "generation_status");
            ensureCommerceModelFailureColumns(connection, mysql);
        } catch (Exception e) {
            log.error("数据库迁移失败: commerce tables", e);
            throw new IllegalStateException("数据库迁移失败: commerce tables", e);
        }
    }

    private void ensureGptImage2TaskTable() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            try (Statement statement = connection.createStatement()) {
                statement.execute(mysql ? mysqlGptImage2TaskSql() : genericGptImage2TaskSql());
            }
            ensureGptImage2TaskColumns(connection, mysql);
            ensureIndex(connection, "idx_gpt_image2_user_created", "gpt_image2_task", "user_id, created_at");
            ensureIndex(connection, "idx_gpt_image2_status", "gpt_image2_task", "status");
        } catch (Exception e) {
            log.error("数据库迁移失败: gpt_image2_task", e);
            throw new IllegalStateException("数据库迁移失败: gpt_image2_task", e);
        }
    }

    private void ensureSubjectReplacementCreditColumns(Connection connection, boolean mysql) throws Exception {
        ensureColumn(connection, mysql, "subject_replacement_task", "deducted_credits",
                "INT DEFAULT NULL COMMENT '已扣除的积分（用于生成失败时返还）'",
                "INT DEFAULT NULL");
        ensureColumn(connection, mysql, "subject_replacement_task", "credits_refunded",
                "TINYINT(1) DEFAULT 0 COMMENT '积分是否已返还'",
                "BOOLEAN DEFAULT FALSE");
    }

    private void ensureGptImage2TaskColumns(Connection connection, boolean mysql) throws Exception {
        ensureColumn(connection, mysql, "gpt_image2_task", "resolution",
                "VARCHAR(10) DEFAULT '2k' COMMENT '清晰度：1k/2k/4k'",
                "VARCHAR(10) DEFAULT '2k'");
        ensureColumn(connection, mysql, "gpt_image2_task", "credit_cost",
                "INT DEFAULT 6 COMMENT '本次生成扣除积分'",
                "INT DEFAULT 6");
        ensureColumn(connection, mysql, "gpt_image2_task", "credits_refunded",
                "TINYINT(1) DEFAULT 0 COMMENT '积分是否已返还'",
                "BOOLEAN DEFAULT FALSE");
    }

    private void ensureCommerceModelFailureColumns(Connection connection, boolean mysql) throws Exception {
        ensureColumn(connection, mysql, "commerce_model", "model_aspect_ratio",
                "VARCHAR(10) DEFAULT '16:9' COMMENT '模特基础图比例'",
                "VARCHAR(10) DEFAULT '16:9'");
        ensureColumn(connection, mysql, "commerce_model", "error_message",
                "VARCHAR(500) DEFAULT NULL COMMENT '模特图生成失败原因'",
                "VARCHAR(500)");
    }

    private void ensureColumn(Connection connection, boolean mysql, String tableName, String columnName,
                              String mysqlDefinition, String genericDefinition) throws Exception {
        if (hasColumn(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " "
                    + (mysql ? mysqlDefinition : genericDefinition));
            log.info("数据库迁移完成: {}.{}", tableName, columnName);
        }
    }

    private String mysqlSubjectReplacementTaskSql() {
        return "CREATE TABLE IF NOT EXISTS subject_replacement_task ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "task_name VARCHAR(100),"
                + "original_video_url VARCHAR(1024) NOT NULL,"
                + "output_video_url VARCHAR(1024),"
                + "thumbnail_url VARCHAR(1024),"
                + "status VARCHAR(20) NOT NULL DEFAULT 'pending',"
                + "aspect_ratio VARCHAR(10) DEFAULT '16:9',"
                + "duration INT DEFAULT 5,"
                + "generate_audio TINYINT(1) DEFAULT 1,"
                + "watermark TINYINT(1) DEFAULT 0,"
                + "model VARCHAR(100),"
                + "prompt TEXT,"
                + "replacements_json TEXT,"
                + "volcengine_task_id VARCHAR(100),"
                + "error_message TEXT,"
                + "submitted_at TIMESTAMP NULL,"
                + "completed_at TIMESTAMP NULL,"
                + "generation_duration INT,"
                + "seed BIGINT,"
                + "deducted_credits INT DEFAULT NULL COMMENT '已扣除的积分（用于生成失败时返还）',"
                + "credits_refunded TINYINT(1) DEFAULT 0 COMMENT '积分是否已返还',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    private String genericSubjectReplacementTaskSql() {
        return "CREATE TABLE IF NOT EXISTS subject_replacement_task ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "task_name VARCHAR(100),"
                + "original_video_url VARCHAR(1024) NOT NULL,"
                + "output_video_url VARCHAR(1024),"
                + "thumbnail_url VARCHAR(1024),"
                + "status VARCHAR(20) NOT NULL DEFAULT 'pending',"
                + "aspect_ratio VARCHAR(10) DEFAULT '16:9',"
                + "duration INT DEFAULT 5,"
                + "generate_audio BOOLEAN DEFAULT TRUE,"
                + "watermark BOOLEAN DEFAULT FALSE,"
                + "model VARCHAR(100),"
                + "prompt CLOB,"
                + "replacements_json CLOB,"
                + "volcengine_task_id VARCHAR(100),"
                + "error_message CLOB,"
                + "submitted_at TIMESTAMP,"
                + "completed_at TIMESTAMP,"
                + "generation_duration INT,"
                + "seed BIGINT,"
                + "deducted_credits INT DEFAULT NULL,"
                + "credits_refunded BOOLEAN DEFAULT FALSE,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    private String mysqlCommerceSeriesSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_series ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "name VARCHAR(120) NOT NULL,"
                + "category VARCHAR(40) NOT NULL DEFAULT 'clothing',"
                + "visual_style VARCHAR(40) NOT NULL DEFAULT 'realistic',"
                + "default_aspect_ratio VARCHAR(10) NOT NULL DEFAULT '9:16',"
                + "enabled_content_types VARCHAR(120) NOT NULL DEFAULT 'talking,try_on',"
                + "status VARCHAR(30) NOT NULL DEFAULT 'active',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "is_deleted TINYINT(1) NOT NULL DEFAULT 0"
                + ")";
    }

    private String genericCommerceSeriesSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_series ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "name VARCHAR(120) NOT NULL,"
                + "category VARCHAR(40) NOT NULL DEFAULT 'clothing',"
                + "visual_style VARCHAR(40) NOT NULL DEFAULT 'realistic',"
                + "default_aspect_ratio VARCHAR(10) NOT NULL DEFAULT '9:16',"
                + "enabled_content_types VARCHAR(120) NOT NULL DEFAULT 'talking,try_on',"
                + "status VARCHAR(30) NOT NULL DEFAULT 'active',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "is_deleted BOOLEAN NOT NULL DEFAULT FALSE"
                + ")";
    }

    private String mysqlCommerceModelSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_model ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "series_id BIGINT NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "name VARCHAR(80) NOT NULL,"
                + "source_type VARCHAR(30) NOT NULL DEFAULT 'ai',"
                + "gender_age VARCHAR(80),"
                + "body_profile VARCHAR(200),"
                + "style_tags VARCHAR(255),"
                + "speaking_persona VARCHAR(200),"
                + "appearance_prompt TEXT,"
                + "base_image_url VARCHAR(1024),"
                + "model_aspect_ratio VARCHAR(10) DEFAULT '16:9',"
                + "status VARCHAR(30) NOT NULL DEFAULT 'generating',"
                + "error_message VARCHAR(500),"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "is_deleted TINYINT(1) NOT NULL DEFAULT 0"
                + ")";
    }

    private String genericCommerceModelSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_model ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "series_id BIGINT NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "name VARCHAR(80) NOT NULL,"
                + "source_type VARCHAR(30) NOT NULL DEFAULT 'ai',"
                + "gender_age VARCHAR(80),"
                + "body_profile VARCHAR(200),"
                + "style_tags VARCHAR(255),"
                + "speaking_persona VARCHAR(200),"
                + "appearance_prompt CLOB,"
                + "base_image_url VARCHAR(1024),"
                + "model_aspect_ratio VARCHAR(10) DEFAULT '16:9',"
                + "status VARCHAR(30) NOT NULL DEFAULT 'generating',"
                + "error_message VARCHAR(500),"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "is_deleted BOOLEAN NOT NULL DEFAULT FALSE"
                + ")";
    }

    private String mysqlCommerceProductSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_product ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "model_id BIGINT NOT NULL,"
                + "series_id BIGINT NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "name VARCHAR(120) NOT NULL,"
                + "image_url VARCHAR(1024) NOT NULL,"
                + "product_type VARCHAR(60),"
                + "selling_points TEXT,"
                + "fabric_fit_color_size TEXT,"
                + "target_audience VARCHAR(255),"
                + "price_offer VARCHAR(255),"
                + "try_on_requirements TEXT,"
                + "status VARCHAR(30) NOT NULL DEFAULT 'active',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "is_deleted TINYINT(1) NOT NULL DEFAULT 0"
                + ")";
    }

    private String genericCommerceProductSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_product ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "model_id BIGINT NOT NULL,"
                + "series_id BIGINT NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "name VARCHAR(120) NOT NULL,"
                + "image_url VARCHAR(1024) NOT NULL,"
                + "product_type VARCHAR(60),"
                + "selling_points CLOB,"
                + "fabric_fit_color_size CLOB,"
                + "target_audience VARCHAR(255),"
                + "price_offer VARCHAR(255),"
                + "try_on_requirements CLOB,"
                + "status VARCHAR(30) NOT NULL DEFAULT 'active',"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "is_deleted BOOLEAN NOT NULL DEFAULT FALSE"
                + ")";
    }

    private String mysqlCommerceProjectSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_project ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "series_id BIGINT NOT NULL,"
                + "model_id BIGINT NOT NULL,"
                + "product_id BIGINT NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "content_type VARCHAR(30) NOT NULL,"
                + "aspect_ratio VARCHAR(10) NOT NULL DEFAULT '9:16',"
                + "model_image_url VARCHAR(1024) NOT NULL,"
                + "product_image_url VARCHAR(1024) NOT NULL,"
                + "status VARCHAR(30) NOT NULL DEFAULT 'draft',"
                + "error_message TEXT,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ")";
    }

    private String genericCommerceProjectSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_project ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "series_id BIGINT NOT NULL,"
                + "model_id BIGINT NOT NULL,"
                + "product_id BIGINT NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "content_type VARCHAR(30) NOT NULL,"
                + "aspect_ratio VARCHAR(10) NOT NULL DEFAULT '9:16',"
                + "model_image_url VARCHAR(1024) NOT NULL,"
                + "product_image_url VARCHAR(1024) NOT NULL,"
                + "status VARCHAR(30) NOT NULL DEFAULT 'draft',"
                + "error_message CLOB,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    private String mysqlCommerceShotSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_shot ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "project_id BIGINT NOT NULL,"
                + "sort_order INT NOT NULL,"
                + "title VARCHAR(120) NOT NULL,"
                + "visual_description TEXT NOT NULL,"
                + "voiceover_text TEXT,"
                + "duration_seconds INT NOT NULL DEFAULT 5,"
                + "video_url VARCHAR(1024),"
                + "generation_status VARCHAR(30) NOT NULL DEFAULT 'draft',"
                + "error_message TEXT,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ")";
    }

    private String genericCommerceShotSql() {
        return "CREATE TABLE IF NOT EXISTS commerce_shot ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "project_id BIGINT NOT NULL,"
                + "sort_order INT NOT NULL,"
                + "title VARCHAR(120) NOT NULL,"
                + "visual_description CLOB NOT NULL,"
                + "voiceover_text CLOB,"
                + "duration_seconds INT NOT NULL DEFAULT 5,"
                + "video_url VARCHAR(1024),"
                + "generation_status VARCHAR(30) NOT NULL DEFAULT 'draft',"
                + "error_message CLOB,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    private String mysqlGptImage2TaskSql() {
        return "CREATE TABLE IF NOT EXISTS gpt_image2_task ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "prompt TEXT NOT NULL,"
                + "aspect_ratio VARCHAR(10) DEFAULT '1:1',"
                + "resolution VARCHAR(10) DEFAULT '2k' COMMENT '清晰度：1k/2k/4k',"
                + "reference_image_url VARCHAR(1024),"
                + "image_url VARCHAR(1024),"
                + "status VARCHAR(20) NOT NULL DEFAULT 'pending',"
                + "model VARCHAR(100),"
                + "mode VARCHAR(30),"
                + "credit_cost INT DEFAULT 6 COMMENT '本次生成扣除积分',"
                + "credits_refunded TINYINT(1) DEFAULT 0 COMMENT '积分是否已返还',"
                + "error_message TEXT,"
                + "submitted_at TIMESTAMP NULL,"
                + "completed_at TIMESTAMP NULL,"
                + "generation_duration INT,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    private String genericGptImage2TaskSql() {
        return "CREATE TABLE IF NOT EXISTS gpt_image2_task ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "prompt CLOB NOT NULL,"
                + "aspect_ratio VARCHAR(10) DEFAULT '1:1',"
                + "resolution VARCHAR(10) DEFAULT '2k',"
                + "reference_image_url VARCHAR(1024),"
                + "image_url VARCHAR(1024),"
                + "status VARCHAR(20) NOT NULL DEFAULT 'pending',"
                + "model VARCHAR(100),"
                + "mode VARCHAR(30),"
                + "credit_cost INT DEFAULT 6,"
                + "credits_refunded BOOLEAN DEFAULT FALSE,"
                + "error_message CLOB,"
                + "submitted_at TIMESTAMP,"
                + "completed_at TIMESTAMP,"
                + "generation_duration INT,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    private void ensureIndex(Connection connection, String indexName, String tableName, String columns) {
        try (Statement statement = connection.createStatement()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            statement.execute(mysql
                    ? "CREATE INDEX " + indexName + " ON " + tableName + " (" + columns + ")"
                    : "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + " (" + columns + ")");
            log.info("数据库索引已创建: {}", indexName);
        } catch (Exception e) {
            log.debug("数据库索引已存在或当前数据库不支持重复创建: {} - {}", indexName, e.getMessage());
        }
    }
}
