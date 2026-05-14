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
            ensureSubjectReplacementTaskTable();
            ensureGptImage2TaskTable();
            ensurePerformanceIndexes();
        };
    }

    private void ensurePropAssetEpisodeIdColumn() {
        try (Connection connection = dataSource.getConnection()) {
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
            ensureIndex(connection, "idx_series_user_deleted_created", "series", "user_id, is_deleted, created_at");
            ensureIndex(connection, "idx_role_series_deleted", "role", "series_id, is_deleted");
        } catch (Exception e) {
            log.warn("性能索引检查失败: {}", e.getMessage());
        }
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

    private void ensureGptImage2TaskTable() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            boolean mysql = productName != null && productName.toLowerCase().contains("mysql");
            try (Statement statement = connection.createStatement()) {
                statement.execute(mysql ? mysqlGptImage2TaskSql() : genericGptImage2TaskSql());
            }
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

    private String mysqlGptImage2TaskSql() {
        return "CREATE TABLE IF NOT EXISTS gpt_image2_task ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "prompt TEXT NOT NULL,"
                + "aspect_ratio VARCHAR(10) DEFAULT '1:1',"
                + "reference_image_url VARCHAR(1024),"
                + "image_url VARCHAR(1024),"
                + "status VARCHAR(20) NOT NULL DEFAULT 'pending',"
                + "model VARCHAR(100),"
                + "mode VARCHAR(30),"
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
                + "reference_image_url VARCHAR(1024),"
                + "image_url VARCHAR(1024),"
                + "status VARCHAR(20) NOT NULL DEFAULT 'pending',"
                + "model VARCHAR(100),"
                + "mode VARCHAR(30),"
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
