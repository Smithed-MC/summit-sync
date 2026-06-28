package net.smithed.summitsync;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PostgresManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("summit-sync-postgres");
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

	private static String url;
	private static String user;
	private static String password;

	public static void init(Dotenv dotenv) {
		url = dotenv.get("POSTGRES_URL");
		if (url == null) {
			url = "jdbc:postgresql://localhost:5432/summit_sync";
		}
		user = dotenv.get("POSTGRES_USER");
		if (user == null) {
			user = "summit_sync";
		}
		password = dotenv.get("POSTGRES_PASSWORD");
		if (password == null) {
			password = "summit_sync_pass";
		}

		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) {
			LOGGER.error("PostgreSQL JDBC driver not found on classpath!", e);
		}

		// Initialize table
		try (Connection conn = getConnection()) {
			try (PreparedStatement stmt = conn.prepareStatement(
				 "CREATE TABLE IF NOT EXISTS player_sync_data (" +
				 "\"key\" VARCHAR(255), " +
				 "uuid VARCHAR(36), " +
				 "data TEXT, " +
				 "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
				 ")"
			 )) {
				stmt.execute();
			}
			try (PreparedStatement stmt = conn.prepareStatement(
				 "ALTER TABLE player_sync_data ADD COLUMN IF NOT EXISTS hash VARCHAR(64)"
			 )) {
				stmt.execute();
			}
			LOGGER.info("Successfully initialized PostgreSQL connection and table.");
		} catch (SQLException e) {
			if ("23505".equals(e.getSQLState()) || "42P07".equals(e.getSQLState())) {
				LOGGER.info("Table player_sync_data already exists or was initialized concurrently.");
			} else {
				LOGGER.error("Failed to initialize PostgreSQL table", e);
			}
		}
	}

	private static Connection getConnection() throws SQLException {
		return DriverManager.getConnection(url, user, password);
	}
	public static void queueSaveTask(String key, String uuid, String data) {
		EXECUTOR.submit(() -> {
			try (Connection conn = getConnection()) {
				// Insert new record
				try (PreparedStatement insertStmt = conn.prepareStatement(
					 "INSERT INTO player_sync_data (\"key\", uuid, data, timestamp) VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
				 )) {
					insertStmt.setString(1, key);
					insertStmt.setString(2, uuid);
					insertStmt.setString(3, data);
					insertStmt.executeUpdate();
					LOGGER.info("Saved new player data for key {} and uuid {} to Postgres.", key, uuid);
				}

				// Prune old records to only preserve the last 20 entries
				String deleteSql = "DELETE FROM player_sync_data " +
				                   "WHERE \"key\" = ? AND uuid = ? AND ctid NOT IN (" +
				                   "  SELECT ctid FROM player_sync_data " +
				                   "  WHERE \"key\" = ? AND uuid = ? " +
				                   "  ORDER BY timestamp DESC LIMIT 20" +
				                   ")";
				try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
					deleteStmt.setString(1, key);
					deleteStmt.setString(2, uuid);
					deleteStmt.setString(3, key);
					deleteStmt.setString(4, uuid);
					int deleted = deleteStmt.executeUpdate();
					if (deleted > 0) {
						LOGGER.info("Pruned {} old entries for key {} and uuid {}.", deleted, key, uuid);
					}
				}
			} catch (SQLException e) {
				LOGGER.error("Failed to save player data to PostgreSQL for key " + key + " and uuid " + uuid, e);
			}
		});
	}

	public static String getLatestData(String key, String uuid) {
		try (Connection conn = getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "SELECT data FROM player_sync_data WHERE \"key\" = ? AND uuid = ? ORDER BY timestamp DESC LIMIT 1"
			 )) {
			stmt.setString(1, key);
			stmt.setString(2, uuid);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					return rs.getString("data");
				}
			}
		} catch (SQLException e) {
			LOGGER.error("Failed to get player data from PostgreSQL for key " + key + " and uuid " + uuid, e);
		}
		return null;
	}

	public static java.util.Map<String, String> getLatestDataForDatabase(String key) {
		java.util.Map<String, String> result = new java.util.HashMap<>();
		String sql = "SELECT DISTINCT ON (uuid) uuid, data FROM player_sync_data WHERE \"key\" = ? ORDER BY uuid, timestamp DESC";
		try (Connection conn = getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, key);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					result.put(rs.getString("uuid"), rs.getString("data"));
				}
			}
		} catch (SQLException e) {
			LOGGER.error("Failed to query database data from PostgreSQL for key " + key, e);
		}
		return result;
	}


	public record SaveEntry(String data, java.sql.Timestamp timestamp) {}

	public static java.util.Map<String, SaveEntry> getLatestDataForUser(String uuid) {
		return getLatestDataForUserWithOffset(uuid, 0);
	}

	public static java.util.Map<String, SaveEntry> getLatestDataForUserWithOffset(String uuid, int index) {
		java.util.Map<String, SaveEntry> result = new java.util.HashMap<>();
		int rowNum = 1 + Math.abs(index);
		String sql = "SELECT \"key\", data, timestamp FROM (" +
		             "  SELECT \"key\", data, timestamp, " +
		             "         ROW_NUMBER() OVER (PARTITION BY \"key\" ORDER BY timestamp DESC) as rn " +
		             "  FROM player_sync_data " +
		             "  WHERE uuid = ?" +
		             ") t " +
		             "WHERE rn = ?";
		try (Connection conn = getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, uuid);
			stmt.setInt(2, rowNum);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					result.put(rs.getString("key"), new SaveEntry(rs.getString("data"), rs.getTimestamp("timestamp")));
				}
			}
		} catch (SQLException e) {
			LOGGER.error("Failed to query user data from PostgreSQL for uuid " + uuid + " and offset " + index, e);
		}
		return result;
	}

	public static void shutdown() {
		EXECUTOR.shutdown();
		try {
			if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
				EXECUTOR.shutdownNow();
			}
		} catch (InterruptedException e) {
			EXECUTOR.shutdownNow();
		}
	}
}
