package filesharing.sync;

import filesharing.main.MainWindow;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class SyncTab {
    private static final String SYNC_DB_URL = "jdbc:sqlite:sync_log.db";
    private TextArea syncLogArea;

    public Tab createTab() {
        Tab tab = new Tab(getResourceString("sync_tab"));
        tab.setClosable(false);

        syncLogArea = new TextArea();
        syncLogArea.setEditable(false);
        Button addSyncFolderButton = new Button(getResourceString("add_sync_folder"));
        Button startSyncButton = new Button(getResourceString("start_sync"));
        Button viewSyncLogButton = new Button(getResourceString("view_sync_log"));

        VBox layout = new VBox(10, new Label(getResourceString("sync_folders")), addSyncFolderButton,
                startSyncButton, viewSyncLogButton, new Label(getResourceString("sync_log")), syncLogArea);
        tab.setContent(layout);

        addSyncFolderButton.setOnAction(e -> addSyncFolder());
        startSyncButton.setOnAction(e -> startSync());
        viewSyncLogButton.setOnAction(e -> viewSyncLog());

        initSyncDatabase();
        return tab;
    }

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void initSyncDatabase() {
        try (Connection conn = DriverManager.getConnection(SYNC_DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS sync_folders (path TEXT PRIMARY KEY)";
            conn.createStatement().execute(sql);
            sql = "CREATE TABLE IF NOT EXISTS sync_log (id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT, action TEXT, timestamp TEXT)";
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void addSyncFolder() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        File dir = dirChooser.showDialog(null);
        if (dir != null) {
            try (Connection conn = DriverManager.getConnection(SYNC_DB_URL)) {
                String sql = "INSERT OR IGNORE INTO sync_folders (path) VALUES (?)";
                PreparedStatement pstmt = conn.prepareStatement(sql);
                pstmt.setString(1, dir.getAbsolutePath());
                pstmt.executeUpdate();
                Platform.runLater(() -> syncLogArea.appendText(getResourceString("sync_folder_added") + dir.getAbsolutePath() + "\n"));
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private void startSync() {
        List<String> syncFolders = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(SYNC_DB_URL)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT path FROM sync_folders");
            while (rs.next()) {
                syncFolders.add(rs.getString("path"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        for (String folder : syncFolders) {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Path path = Paths.get(folder);
                path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                new Thread(() -> {
                    try {
                        while (true) {
                            WatchKey key = watchService.take();
                            for (WatchEvent<?> event : key.pollEvents()) {
                                Path filePath = path.resolve((Path) event.context());
                                syncFile(filePath.toFile());
                            }
                            key.reset();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();
                Platform.runLater(() -> syncLogArea.appendText(getResourceString("sync_started") + folder + "\n"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void syncFile(File file) {
        MainWindow mainWindow = new MainWindow();
        Map<String, String> devices = mainWindow.getDiscoveredDevices();
        devices.forEach((name, address) -> {
            try (SSLSocket socket = (SSLSocket) MainWindow.sslContext.getSocketFactory().createSocket(address, 12345)) {
                socket.startHandshake();
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                FileInputStream fis = new FileInputStream(file);
                FileChannel inChannel = fis.getChannel();

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileInputStream hashFis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = hashFis.read(buffer)) > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
                String fileHash = MainWindow.bytesToHex(digest.digest());
                String metadata = String.format("Size: %d bytes, Modified: %s", file.length(), new Date(file.lastModified()));

                dos.writeUTF(MainWindow.userUUID);
                dos.writeUTF("FILE");
                dos.writeUTF(file.getName());
                dos.writeLong(file.length());
                dos.writeUTF(metadata);
                dos.writeUTF(fileHash);
                dos.writeUTF("sync");

                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (inChannel.read(buffer) != -1) {
                    buffer.flip();
                    dos.write(buffer.array(), 0, buffer.limit());
                    buffer.clear();
                }
                inChannel.close();
                fis.close();
                logSync(file.getName(), "전송");
                Platform.runLater(() -> syncLogArea.appendText(getResourceString("file_synced") + file.getName() + " to " + name + "\n"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void logSync(String fileName, String action) {
        try (Connection conn = DriverManager.getConnection(SYNC_DB_URL)) {
            String sql = "INSERT INTO sync_log (file_name, action, timestamp) VALUES (?, ?, ?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, fileName);
            pstmt.setString(2, action);
            pstmt.setString(3, new java.util.Date().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void viewSyncLog() {
        ListView<String> logView = new ListView<>();
        try (Connection conn = DriverManager.getConnection(SYNC_DB_URL)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM sync_log");
            while (rs.next()) {
                logView.getItems().add(String.format("%s: %s at %s",
                        rs.getString("action"), rs.getString("file_name"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        Stage logStage = new Stage();
        logStage.setTitle(getResourceString("sync_log"));
        logStage.setScene(new Scene(new VBox(new Label(getResourceString("sync_log")), logView), 400, 300));
        logStage.show();
    }
}