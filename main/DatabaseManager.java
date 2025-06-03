package filesharing.main;

import java.io.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.commons.csv.*;

public class DatabaseManager {
    private static final String TRANSFER_DB = "transfers.db";
    private static final String CHAT_DB = "chats.db";
    private static final String DOWNLOAD_DB = "downloads.db";
    private static final String ACTIVITY_DB = "activities.db";
    private static final String VERSION_DB = "versions.db";

    public DatabaseManager() {
        initDatabases();
    }

    private void initDatabases() {
        try (Connection conn = getTransferConnection()) {
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS transfers (file_name TEXT, type TEXT, size INTEGER, timestamp TEXT, metadata TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS tags (file_name TEXT, tags TEXT, timestamp TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS blocked_users (uuid TEXT, block_files BOOLEAN, block_messages BOOLEAN)");
            stmt.execute("CREATE TABLE IF NOT EXISTS contacts (uuid TEXT PRIMARY KEY, grade TEXT)");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        try (Connection conn = getChatConnection()) {
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS chats (uuid TEXT, message TEXT, type TEXT, timestamp TEXT)");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        try (Connection conn = getDownloadConnection()) {
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS downloads (file_name TEXT, timestamp TEXT, metadata TEXT)");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        try (Connection conn = getActivityConnection()) {
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS activities (uuid TEXT, action TEXT, timestamp TEXT)");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        try (Connection conn = getVersionConnection()) {
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS versions (file_name TEXT, version_name TEXT, size INTEGER, hash TEXT, timestamp TEXT)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Connection getTransferConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + TRANSFER_DB);
    }

    public Connection getChatConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + CHAT_DB);
    }

    public Connection getDownloadConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + DOWNLOAD_DB);
    }

    public Connection getActivityConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + ACTIVITY_DB);
    }

    public Connection getVersionConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + VERSION_DB);
    }

    public void logTransfer(String fileName, String type, long size, String metadata) {
        try (Connection conn = getTransferConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT INTO transfers (file_name, type, size, timestamp, metadata) VALUES (?, ?, ?, ?, ?)")) {
            pstmt.setString(1, fileName);
            pstmt.setString(2, type);
            pstmt.setLong(3, size);
            pstmt.setString(4, LocalDateTime.now().toString());
            pstmt.setString(5, metadata);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logChat(String uuid, String message, String type) {
        try (Connection conn = getChatConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT INTO chats (uuid, message, type, timestamp) VALUES (?, ?, ?, ?)")) {
            pstmt.setString(1, uuid);
            pstmt.setString(2, message);
            pstmt.setString(3, type);
            pstmt.setString(4, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logDownload(String fileName, String metadata) {
        try (Connection conn = getDownloadConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT INTO downloads (file_name, timestamp, metadata) VALUES (?, ?, ?)")) {
            pstmt.setString(1, fileName);
            pstmt.setString(2, LocalDateTime.now().toString());
            pstmt.setString(3, metadata);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logTags(String fileName, String tags) {
        try (Connection conn = getTransferConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT INTO tags (file_name, tags, timestamp) VALUES (?, ?, ?)")) {
            pstmt.setString(1, fileName);
            pstmt.setString(2, tags);
            pstmt.setString(3, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logActivity(String uuid, String action) {
        try (Connection conn = getActivityConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT INTO activities (uuid, action, timestamp) VALUES (?, ?, ?)")) {
            pstmt.setString(1, uuid);
            pstmt.setString(2, action);
            pstmt.setString(3, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logUpdateActivity(String action, String version, String developer, boolean success) {
        String detailedAction = String.format("Update: %s (Version: %s, Developer: %s, Status: %s)", 
                                             action, version, developer, success ? "Success" : "Failed");
        logActivity(UUID.randomUUID().toString(), detailedAction);
    }

    public void logFileVersion(String fileName, String versionName, long size, String hash) {
        try (Connection conn = getVersionConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT INTO versions (file_name, version_name, size, hash, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            pstmt.setString(1, fileName);
            pstmt.setString(2, versionName);
            pstmt.setLong(3, size);
            pstmt.setString(4, hash);
            pstmt.setString(5, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void blockUser(String uuid, boolean blockFiles, boolean blockMessages) {
        try (Connection conn = getTransferConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT OR REPLACE INTO blocked_users (uuid, block_files, block_messages) VALUES (?, ?, ?)")) {
            pstmt.setString(1, uuid);
            pstmt.setBoolean(2, blockFiles);
            pstmt.setBoolean(3, blockMessages);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean isFileBlocked(String uuid) {
        try (Connection conn = getTransferConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT block_files FROM blocked_users WHERE uuid = ?")) {
            pstmt.setString(1, uuid);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getBoolean("block_files");
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean isMessageBlocked(String uuid) {
        try (Connection conn = getTransferConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT block_messages FROM blocked_users WHERE uuid = ?")) {
            pstmt.setString(1, uuid);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getBoolean("block_messages");
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void setContactGrade(String uuid, String grade) {
        try (Connection conn = getTransferConnection();
             PreparedStatement pstmt = conn.prepareStatement("INSERT OR REPLACE INTO contacts (uuid, grade) VALUES (?, ?)")) {
            pstmt.setString(1, uuid);
            pstmt.setString(2, grade);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getContactGrade(String uuid) {
        try (Connection conn = getTransferConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT grade FROM contacts WHERE uuid = ?")) {
            pstmt.setString(1, uuid);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getString("grade") : "Green";
        } catch (SQLException e) {
            e.printStackTrace();
            return "Green";
        }
    }

    public void exportBackup(String backupPath) throws SQLException, IOException {
        try (Connection transferConn = getTransferConnection();
             Connection chatConn = getChatConnection();
             CSVPrinter printer = new CSVPrinter(new FileWriter(backupPath), CSVFormat.DEFAULT.withHeader("table", "data"))) {
            Statement stmt = transferConn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM transfers");
            while (rs.next()) {
                printer.printRecord("transfers", String.format("%s,%s,%d,%s,%s",
                        rs.getString("file_name"), rs.getString("type"), rs.getLong("size"),
                        rs.getString("timestamp"), rs.getString("metadata")));
            }
            rs = stmt.executeQuery("SELECT * FROM tags");
            while (rs.next()) {
                printer.printRecord("tags", String.format("%s,%s,%s",
                        rs.getString("file_name"), rs.getString("tags"), rs.getString("timestamp")));
            }
            stmt = chatConn.createStatement();
            rs = stmt.executeQuery("SELECT * FROM chats");
            while (rs.next()) {
                printer.printRecord("chats", String.format("%s,%s,%s,%s",
                        rs.getString("uuid"), rs.getString("message"), rs.getString("type"), rs.getString("timestamp")));
            }
        }
    }
}