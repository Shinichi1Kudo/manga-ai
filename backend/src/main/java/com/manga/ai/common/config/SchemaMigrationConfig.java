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
            ensureIndex(connection, "idx_subject_replacement_user_created", "subject_replacement_task", "user_id, created_at");
            ensureIndex(connection, "idx_subject_replacement_status", "subject_replacement_task", "status");
        } catch (Exception e) {
            log.error("数据库迁移失败: subject_replacement_task", e);
            throw new IllegalStateException("数据库迁移失败: subject_replacement_task", e);
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
