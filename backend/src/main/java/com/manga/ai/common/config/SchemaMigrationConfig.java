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
