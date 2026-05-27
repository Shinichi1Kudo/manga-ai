package com.manga.ai.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationConfigTest {

    @Test
    void migrationRunnerCreatesCommerceTablesForDeploymentsWithoutFlyway() throws Exception {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:commerce_schema;MODE=MySQL;DB_CLOSE_DELAY=-1");
        SchemaMigrationConfig config = new SchemaMigrationConfig(dataSource);

        config.propAssetEpisodeIdMigrationRunner().run(null);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "commerce_series")).isTrue();
            assertThat(tableExists(connection, "commerce_model")).isTrue();
            assertThat(tableExists(connection, "commerce_product")).isTrue();
            assertThat(tableExists(connection, "commerce_project")).isTrue();
            assertThat(columnExists(connection, "commerce_project", "try_on_requirements")).isTrue();
            assertThat(tableExists(connection, "commerce_shot")).isTrue();
        }
    }

    @Test
    void migrationRunnerAllowsPhoneOnlyUsersInLegacyEmailRequiredTable() throws Exception {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:user_phone_schema;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE user (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        email VARCHAR(100) NOT NULL UNIQUE,
                        password VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE email_verification (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        email VARCHAR(100) NOT NULL,
                        code VARCHAR(6) NOT NULL,
                        type VARCHAR(20) NOT NULL,
                        expired_at TIMESTAMP NOT NULL,
                        used INT DEFAULT 0,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
        SchemaMigrationConfig config = new SchemaMigrationConfig(dataSource);

        config.propAssetEpisodeIdMigrationRunner().run(null);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(columnExists(connection, "user", "phone")).isTrue();
            assertThat(columnExists(connection, "email_verification", "phone")).isTrue();
            statement.execute("INSERT INTO user (phone, password) VALUES ('13800138000', 'encoded')");
            statement.execute("""
                    INSERT INTO email_verification (phone, code, type, expired_at, used)
                    VALUES ('13800138000', '123456', 'login', CURRENT_TIMESTAMP, 0)
                    """);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, tableName.toUpperCase(), null)) {
            return tables.next();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return columns.next();
        }
    }
}
