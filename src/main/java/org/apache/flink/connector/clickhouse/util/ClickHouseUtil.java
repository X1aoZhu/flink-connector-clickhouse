package org.apache.flink.connector.clickhouse.util;

import org.apache.flink.connector.clickhouse.internal.common.DistributedEngineFullSchema;

import org.apache.http.client.utils.URIBuilder;
import ru.yandex.clickhouse.ClickHouseConnection;

import javax.annotation.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** clickhouse util. */
public class ClickHouseUtil {

    private static final Pattern DISTRIBUTED_TABLE_ENGINE_PATTERN =
            Pattern.compile(
                    "Distributed\\((?<cluster>[a-zA-Z_][0-9a-zA-Z_]*),\\s*(?<database>[a-zA-Z_][0-9a-zA-Z_]*),\\s*(?<table>[a-zA-Z_][0-9a-zA-Z_]*)");

    private static final String QUERY_TABLE_ENGINE_SQL =
            "SELECT engine_full FROM system.tables WHERE database = ? AND name = ?";

    public static String getJdbcUrl(String url, @Nullable String database) {
        try {
            database = database != null ? database : "";
            return "jdbc:" + (new URIBuilder(url)).setPath("/" + database).build().toString();
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Cannot parse url: %s", url), e);
        }
    }

    public static DistributedEngineFullSchema getAndParseEngineFullSchema(
            ClickHouseConnection connection, String databaseName, String tableName)
            throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(QUERY_TABLE_ENGINE_SQL)) {
            stmt.setString(1, databaseName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String engineFull = rs.getString("engine_full");
                    Matcher matcher =
                            DISTRIBUTED_TABLE_ENGINE_PATTERN.matcher(engineFull.replace("'", ""));
                    if (matcher.find()) {
                        String cluster = matcher.group("cluster");
                        String database = matcher.group("database");
                        String table = matcher.group("table");
                        return DistributedEngineFullSchema.of(cluster, database, table);
                    } else {
                        return null;
                    }
                }
            }
        }

        throw new SQLException(
                String.format("table `%s`.`%s` does not exist", databaseName, tableName));
    }
}
