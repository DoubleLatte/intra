package filesharing.main;

import java.sql.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import java.io.FileWriter;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:transfer_log.db";
    private static final String CHAT_DB_URL = "jdbc:sqlite:chat_log.db";
    private static final String USERBOOK_DB_URL = "jdbc:sqlite:userbook.db";
    private static final String TAG_DB_URL = "jdbc:sqlite:tag_log.db";
    private static final String CONTACT_DB_URL = "jdbc:sqlite:contact.db";
    private static final String DOWNLOAD_DB_URL = "jdbc:sqlite:download_log.db";
    private static Map<String, Boolean> userFileBlock = new ConcurrentHashMap<>();
    private static Map<String, Boolean> userMessageBlock = new ConcurrentHashMap<>();
    private static Map<String, String> contactGrades = new ConcurrentHashMap<>();

    public void initDatabases() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS transfers (id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT, type TEXT, size LONG, metadata TEXT, timestamp TEXT)";
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try (Connection conn = DriverManager.getConnection(CHAT_DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS chats (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, message TEXT, type TEXT, timestamp TEXT)";
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try (Connection conn = DriverManager.getConnection(USERBOOK_DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS userbook (uuid TEXT PRIMARY KEY, file_block BOOLEAN, message_block BOOLEAN)";
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try (Connection conn = DriverManager.getConnection(TAG_DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS tags (file_name TEXT, tags TEXT, timestamp TEXT)";
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try (Connection conn = DriverManager.getConnection(CONTACT_DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS contacts (uuid TEXT PRIMARY KEY, grade TEXT)";
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try (Connection conn = DriverManager.getConnection(DOWNLOAD_DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT, metadata TEXT, timestamp TEXT)";
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logTransfer(String fileName, String type, long size, String metadata) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String sql = "INSERT INTO transfers (file_name, type, size, metadata, timestamp) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, fileName);
                pstmt.setString(2, type);
                pstmt.setLong(3, size);
                pstmt.setString(4, metadata);
                pstmt.setString(5, new java.util.Date().toString());
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logChat(String uuid, String message, String type) {
        try (Connection conn = DriverManager.getConnection(CHAT_DB_URL)) {
            String sql = "INSERT INTO chats (uuid, message, type, timestamp) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                pstmt.setString(2, message);
                pstmt.setString(3, type);
                pstmt.setString(4, new java.util.Date().toString());
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logTags(String fileName, String tags) {
        try (Connection conn = DriverManager.getConnection(TAG_DB_URL)) {
            String sql = "INSERT INTO tags (file_name, tags, timestamp) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, fileName);
                pstmt.setString(2, tags);
                pstmt.setString(3, new java.util.Date().toString());
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void logDownload(String fileName, String metadata) {
        try (Connection conn = DriverManager.getConnection(DOWNLOAD_DB_URL)) {
            String sql = "INSERT INTO downloads (file_name, metadata, timestamp) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, fileName);
                pstmt.setString(2, metadata);
                pstmt.setString(3, new java.util.Date().toString());
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void blockUser(String uuid, boolean fileBlock, boolean messageBlock) {
        userFileBlock.put(uuid, fileBlock);
        userMessageBlock.put(uuid, messageBlock);
        try (Connection conn = DriverManager.getConnection(USERBOOK_DB_URL)) {
            String sql = "INSERT OR REPLACE INTO userbook (uuid, file_block, message_block) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                pstmt.setBoolean(2, fileBlock);
                pstmt.setBoolean(3, messageBlock);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void setContactGrade(String uuid, String grade) {
        contactGrades.put(uuid, grade);
        try (Connection conn = DriverManager.getConnection(CONTACT_DB_URL)) {
            String sql = "INSERT OR REPLACE INTO contacts (uuid, grade) VALUES (?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                pstmt.setString(2, grade);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void exportBackup(String backupPath) throws Exception {
        try (CSVPrinter printer = new CSVPrinter(new FileWriter(backupPath), CSVFormat.DEFAULT)) {
            printer.printRecord("ID", "File Name", "Type", "Size", "Metadata", "Timestamp");
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM transfers");
                while (rs.next()) {
                    printer.printRecord(rs.getInt("id"), rs.getString("file_name"),
                            rs.getString("type"), rs.getLong("size"),
                            rs.getString("metadata"), rs.getString("timestamp"));
                }
            }
            try (Connection conn = DriverManager.getConnection(CHAT_DB_URL)) {
                ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM chats");
                printer.printRecord("Chats");
                printer.printRecord("ID", "UUID", "Message", "Type", "Timestamp");
                while (rs.next()) {
                    printer.printRecord(rs.getInt("id"), rs.getString("uuid"),
                            rs.getString("message"), rs.getString("type"), rs.getString("timestamp"));
                }
            }
        }
    }

    public void startLogCleanup() {
        new Thread(() -> {
            while (true) {
                try {
                    try (Connection conn = DriverManager.getConnection(DB_URL)) {
                        String sql = "DELETE FROM transfers WHERE timestamp < datetime('now', '-30 days')";
                        conn.createStatement().executeUpdate(sql);
                    }
                    try (Connection conn = DriverManager.getConnection(CHAT_DB_URL)) {
                        String sql = "DELETE FROM chats WHERE timestamp < datetime('now', '-30 days')";
                        conn.createStatement().executeUpdate(sql);
                    }
                    Platform.runLater(() -> notify(getResourceString("log_cleanup_completed")));
                    Thread.sleep(86400000); // 1일
                } catch (Exception e) {
                    Platform.runLater(() -> notify("Log cleanup error: " + e.getMessage()));
                }
            }
        }).start();
    }

    public boolean isFileBlocked(String uuid) {
        return userFileBlock.getOrDefault(uuid, false);
    }

    public boolean isMessageBlocked(String uuid) {
        return userMessageBlock.getOrDefault(uuid, false);
    }

    public String getContactGrade(String uuid) {
        return contactGrades.getOrDefault(uuid, "Green");
    }

    public Connection getTransferConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public Connection getChatConnection() throws SQLException {
        return DriverManager.getConnection(CHAT_DB_URL);
    }

    public Connection getDownloadConnection() throws SQLException {
        return DriverManager.getConnection(DOWNLOAD_DB_URL);
    }

    private String getResourceString(String key) {
        return java.util.ResourceBundle.getBundle("messages", java.util.Locale.getDefault()).getString(key);
    }

    private void notify(String message) {
        Platform.runLater(() -> System.out.println(message)); // UIManager로 전달
    }
}