//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.apache.flink.connector.clickhouse.internal.connection;

import org.apache.flink.connector.clickhouse.internal.options.ClickHouseOptions;
import org.apache.flink.connector.clickhouse.util.ClickHouseUtil;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.yandex.clickhouse.BalancedClickhouseDataSource;
import ru.yandex.clickhouse.ClickHouseConnection;
import ru.yandex.clickhouse.settings.ClickHouseProperties;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ClickHouse connection provider. Use ClickHouseDriver to create a connection. */
public class ClickHouseConnectionProvider implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseConnectionProvider.class);

    private static final Pattern HTTP_PORT_PATTERN =
            Pattern.compile("You must use port (?<port>[0-9]+) for HTTP.");

    /**
     * Query different shard info
     *
     * <p>TODO: Should consider `shard_weight` when writing data into different shards, may be also
     * `replica_num`.
     */
    private static final String QUERY_CLUSTER_INFO_SQL =
            "SELECT shard_num, host_address, port FROM system.clusters WHERE cluster = ? and replica_num = 1 ORDER BY shard_num ASC";

    private final ClickHouseOptions options;

    private transient ClickHouseConnection connection;

    private transient List<ClickHouseConnection> shardConnections;

    public ClickHouseConnectionProvider(ClickHouseOptions options) {
        this.options = options;
    }

    public synchronized ClickHouseConnection getOrCreateConnection() throws SQLException {
        if (connection == null) {
            connection = createConnection(options.getUrl(), options.getDatabaseName());
        }
        return connection;
    }

    public synchronized List<ClickHouseConnection> getOrCreateShardConnections(
            String shardCluster, String shardDatabase) throws SQLException {
        if (shardConnections == null) {
            shardConnections = createShardConnections(shardCluster, shardDatabase);
        }

        return shardConnections;
    }

    private List<ClickHouseConnection> createShardConnections(
            String remoteCluster, String remoteDatabase) throws SQLException {
        List<ClickHouseConnection> shardConnections = new ArrayList<>();
        ClickHouseConnection conn = getOrCreateConnection();
        try (PreparedStatement stmt = conn.prepareStatement(QUERY_CLUSTER_INFO_SQL)) {
            stmt.setString(1, remoteCluster);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String host = rs.getString("host_address");
                    int port = getActualHttpPort(host, rs.getInt("port"));
                    String url = "clickhouse://" + host + ":" + port;
                    shardConnections.add(createConnection(url, remoteDatabase));
                }
            }
        }

        if (shardConnections.isEmpty()) {
            throw new SQLException("unable to query shards in system.clusters");
        }

        return shardConnections;
    }

    private ClickHouseConnection createConnection(String url, String database) throws SQLException {
        LOG.info("connecting to {}, database {}", url, database);

        String jdbcUrl = ClickHouseUtil.getJdbcUrl(url, database);
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUser(options.getUsername().orElse(null));
        properties.setPassword(options.getPassword().orElse(null));
        BalancedClickhouseDataSource dataSource =
                new BalancedClickhouseDataSource(jdbcUrl, properties);
        if (dataSource.getAllClickhouseUrls().size() > 1) {
            dataSource.actualize();
        }
        return dataSource.getConnection();
    }

    public void closeConnections() throws SQLException {
        if (this.connection != null) {
            connection.close();
        }

        if (shardConnections != null) {
            for (ClickHouseConnection shardConnection : this.shardConnections) {
                shardConnection.close();
            }
        }
    }

    private int getActualHttpPort(String host, int port) throws SQLException {
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpGet request =
                    new HttpGet(
                            (new URIBuilder())
                                    .setScheme("http")
                                    .setHost(host)
                                    .setPort(port)
                                    .build());
            HttpResponse response = httpclient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                String raw = EntityUtils.toString(response.getEntity());
                Matcher matcher = HTTP_PORT_PATTERN.matcher(raw);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group("port"));
                }
                throw new SQLException("Cannot query ClickHouse http port.");
            }

            return port;
        } catch (Throwable throwable) {
            throw new SQLException("Cannot connect to ClickHouse server using HTTP.", throwable);
        }
    }
}
