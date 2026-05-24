package com.manga.ai.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

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
