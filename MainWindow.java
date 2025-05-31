import javafx.application.Platform;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.zip.*;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import oshi.SystemInfo;
import oshi.hardware.NetworkIF;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

public class MainWindow {
    private static final String SERVICE_TYPE = "_fileshare._tcp.local.";
    private static final int PORT = 12345;
    private static final int MULTICAST_PORT = 4446;
    private static final String MULTICAST_ADDRESS = "239.255.0.1";
    private static final String DB_URL = "jdbc:sqlite:transfer_log.db";
    private static final String CHAT_DB_URL = "jdbc:sqlite:chat_log.db";
    private static final String USERBOOK_DB_URL = "jdbc:sqlite:userbook.db";
    private static final String TAG_DB_URL = "jdbc:sqlite:tag_log.db";
    private static JmDNS jmdns;
    private static Map<String, String> discoveredDevices = new HashMap<>();
    private static Map<String, String> deviceStatus = new HashMap<>();
    private static Set<String> blockedUUIDs = new HashSet<>();
    private static Map<String, Boolean> userFileBlock = new HashMap<>();
    private static Map<String, Boolean> userMessageBlock = new HashMap<>();
    private static String userUUID = UUID.randomUUID().toString();
    private static String userName = "User_" + userUUID.substring(0, 8);
    private static String avatarPath = "";
    private static boolean autoAcceptFiles = false;
    private static long transferSpeedLimit = 0;
    private static String savePath = System.getProperty("user.home") + "/Downloads";
    private static final int CHUNK_SIZE = 8192;
    private ListView<String> deviceListView;
    private TextArea chatArea;
    private TextField chatInput;
    private ProgressBar progressBar;
    private TrayIcon trayIcon;
    private TextField manualIpInput;
    private ImageView avatarView;
    private static SystemInfo systemInfo = new SystemInfo();
    private static MediaPlayer mediaPlayer;

    public Tab createTab() {
        Tab tab = new Tab(getResourceString("main_tab"));
        tab.setClosable(false);

        deviceListView = new ListView<>();
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatInput = new TextField();
        Button sendChatButton = new Button(getResourceString("send_chat"));
        Button sendFileButton = new Button(getResourceString("send_file_folder"));
        CheckBox autoAcceptCheckBox = new CheckBox(getResourceString("auto_accept_files"));
        Button viewLogButton = new Button(getResourceString("view_transfer_log"));
        Button viewChatLogButton = new Button(getResourceString("view_chat_log"));
        Button blockUserButton = new Button(getResourceString("block_user"));
        Button manageGroupsButton = new Button(getResourceString("manage_groups"));
        Button viewTagsButton = new Button(getResourceString("view_tags"));
        Button viewStatsButton = new Button(getResourceString("view_stats"));
        Button networkDiagnosticsButton = new Button(getResourceString("network_diagnostics"));
        manualIpInput = new TextField();
        manualIpInput.setPromptText(getResourceString("manual_ip_prompt"));
        Button addManualIpButton = new Button(getResourceString("add_manual_ip"));
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        avatarView = new ImageView();
        avatarView.setFitWidth(50);
        avatarView.setFitHeight(50);
        updateAvatar();

        VBox controls = new VBox(10, new Label(getResourceString("device_list")), deviceListView,
                new Label(getResourceString("chat")), chatArea, chatInput, sendChatButton,
                sendFileButton, autoAcceptCheckBox, viewLogButton, viewChatLogButton,
                blockUserButton, manageGroupsButton, viewTagsButton, viewStatsButton,
                networkDiagnosticsButton, new Label(getResourceString("manual_ip")), manualIpInput,
                addManualIpButton, progressBar, new Label(getResourceString("avatar")), avatarView);
        tab.setContent(controls);

        sendChatButton.setOnAction(e -> sendChat());
        sendFileButton.setOnAction(e -> sendFileOrFolder());
        autoAcceptCheckBox.setOnAction(e -> autoAcceptFiles = autoAcceptCheckBox.isSelected());
        viewLogButton.setOnAction(e -> showTransferLog());
        viewChatLogButton.setOnAction(e -> showChatLog());
        blockUserButton.setOnAction(e -> blockUser());
        manageGroupsButton.setOnAction(e -> manageGroups());
        viewTagsButton.setOnAction(e -> viewTags());
        viewStatsButton.setOnAction(e -> showStats());
        networkDiagnosticsButton.setOnAction(e -> runNetworkDiagnostics());
        addManualIpButton.setOnAction(e -> addManualDevice());
        startAutoBackup();

        try {
            setupMDNS();
            new Thread(this::startServer).start();
            new Thread(this::startMulticastListener).start();
            setupSystemTray();
            initDatabases();
            setupMediaPlayer();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return tab;
    }

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void setupMDNS() throws IOException {
        jmdns = JmDNS.create(InetAddress.getLocalHost());
        jmdns.addServiceListener(SERVICE_TYPE, new ServiceListener() {
            @Override
            public void serviceAdded(ServiceEvent event) {
                Platform.runLater(() -> notify(getResourceString("device_discovered") + event.getName()));
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                String name = event.getName();
                Platform.runLater(() -> {
                    discoveredDevices.remove(name);
                    deviceStatus.remove(name);
                    deviceListView.getItems().remove(name);
                    notify(getResourceString("device_removed") + name);
                });
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                String name = event.getName();
                String address = event.getInfo().getInetAddresses()[0].getHostAddress();
                Platform.runLater(() -> {
                    discoveredDevices.put(name, address);
                    deviceStatus.put(name, getResourceString("online"));
                    deviceListView.getItems().add(name);
                    notify(getResourceString("device_info") + name + " (" + address + ")");
                });
                checkDeviceStatus(address, name);
            }
        });

        jmdns.registerService(javax.jmdns.ServiceInfo.create(
                SERVICE_TYPE, userName + "_" + userUUID, PORT, ""));
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket socket) {
        try {
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            String uuid = dis.readUTF();
            if (blockedUUIDs.contains(uuid) || !validateUUID(uuid)) {
                socket.close();
                return;
            }
            String type = dis.readUTF();

            if (type.equals("CHAT") && !userMessageBlock.getOrDefault(uuid, false)) {
                String message = dis.readUTF();
                logChat(uuid, message, "수신");
                Platform.runLater(() -> chatArea.appendText(getResourceString("received") + message + "\n"));
                playNotificationSound();
            } else if (type.equals("FILE") && !userFileBlock.getOrDefault(uuid, false)) {
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                String hash = dis.readUTF();
                String tags = dis.readUTF();
                Platform.runLater(() -> {
                    if (autoAcceptFiles) {
                        receiveFile(fileName, fileSize, hash, tags, dis);
                    } else {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                                getResourceString("file_receive_prompt") + fileName + " (" + fileSize + " bytes)?");
                        alert.showAndWait().ifPresent(response -> {
                            if (response.getButtonData().isDefaultButton()) {
                                receiveFile(fileName, fileSize, hash, tags, dis);
                            }
                        });
                    }
                });
            } else if (type.equals("STATUS")) {
                String name = dis.readUTF();
                Platform.runLater(() -> deviceStatus.put(name, getResourceString("online")));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void receiveFile(String fileName, long fileSize, String expectedHash, String tags, DataInputStream dis) {
        try {
            File saveDir = new File(savePath);
            if (!saveDir.exists()) saveDir.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(saveDir, fileName));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[CHUNK_SIZE];
            long bytesRead = 0;
            int count;
            long startTime = System.currentTimeMillis();
            while (bytesRead < fileSize && (count = dis.read(buffer, 0, Math.min(CHUNK_SIZE, (int)(fileSize - bytesRead)))) > 0) {
                fos.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                bytesRead += count;
                double progress = (double) bytesRead / fileSize;
                Platform.runLater(() -> progressBar.setProgress(progress));
                if (transferSpeedLimit > 0) {
                    throttleTransfer(bytesRead, startTime);
                }
            }
            fos.close();
            String receivedHash = bytesToHex(digest.digest());
            if (!receivedHash.equals(expectedHash)) {
                Platform.runLater(() -> notify(getResourceString("file_integrity_failed")));
                return;
            }
            logTransfer(fileName, "수신", fileSize);
            logTags(fileName, tags);
            Platform.runLater(() -> {
                progressBar.setProgress(0);
                progressBar.setVisible(false);
                notify(getResourceString("file_received") + fileName);
                playNotificationSound();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void throttleTransfer(long bytesRead, long startTime) {
        if (transferSpeedLimit <= 0) return;
        long elapsed = System.currentTimeMillis() - startTime;
        long expectedTime = (bytesRead * 1000) / transferSpeedLimit;
        if (elapsed < expectedTime) {
            try {
                Thread.sleep(expectedTime - elapsed);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void sendChat() {
        String target = deviceListView.getSelectionModel().getSelectedItem();
        String message = chatInput.getText();
        if (target == null || message.isEmpty()) return;

        String address = discoveredDevices.get(target);
        if (address == null) {
            notify(getResourceString("device_not_found"));
            return;
        }

        try (Socket socket = new Socket(address, PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
            dos.writeUTF(userUUID);
            dos.writeUTF("CHAT");
            dos.writeUTF(message);
            logChat(userUUID, message, "전송");
            Platform.runLater(() -> chatArea.appendText(getResourceString("sent") + message + "\n"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        chatInput.clear();
    }

    private void startMulticastListener() {
        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.joinGroup(group);
            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                String senderUUID = message.split(":")[0].split("_")[1].trim();
                if (!userMessageBlock.getOrDefault(senderUUID, false)) {
                    logChat(senderUUID, message, "수신");
                    Platform.runLater(() -> chatArea.appendText(getResourceString("group") + message + "\n"));
                    playNotificationSound();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendFileOrFolder() {
        String target = deviceListView.getSelectionModel().getSelectedItem();
        if (target == null) {
            notify(getResourceString("select_device"));
            return;
        }
        String address = discoveredDevices.get(target);
        if (address == null) {
            notify(getResourceString("device_not_found"));
            return;
        }

        TextInputDialog tagDialog = new TextInputDialog();
        tagDialog.setHeaderText(getResourceString("enter_tags"));
        Optional<String> tags = tagDialog.showAndWait();

        FileChooser fileChooser = new FileChooser();
        File file = fileChooser.showOpenDialog(null);
        if (file == null) return;

        try {
            String fileName;
            File sendFile;
            if (file.isDirectory()) {
                fileName = file.getName() + ".zip";
                sendFile = new File(fileName);
                zipFolder(file, sendFile);
            } else {
                fileName = file.getName();
                sendFile = file;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(sendFile)) {
                byte[] buffer = new byte[CHUNK_SIZE]; 
                int count;
                while ((count = fis.read(buffer)) > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            String fileHash = bytesToHex(digest.digest());

            try (Socket socket = new Socket(address, PORT);
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 FileInputStream fis = new FileInputStream(sendFile)) {
                dos.writeUTF(userUUID);
                dos.writeUTF("FILE");
                dos.writeUTF(fileName);
                dos.writeLong(sendFile.length());
                dos.writeUTF(fileHash);
                dos.writeUTF(tags.orElse(""));

                byte[] buffer = new byte[CHUNK_SIZE];
                long bytesRead = 0;
                int count;
                long startTime = System.currentTimeMillis();
                progressBar.setVisible(true);
                while ((count = fis.read(buffer)) > 0) {
                    dos.write(buffer, 0, count);
                    bytesRead += count;
                    double progress = (double) bytesRead / sendFile.length();
                    Platform.runLater(() -> progressBar.setProgress(progress));
                    if (transferSpeedLimit > 0) {
                        throttleTransfer(bytesRead, startTime);
                    }
                }
                logTransfer(fileName, "전송", sendFile.length());
                logTags(fileName, tags.orElse(""));
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    notify(getResourceString("file_sent") + fileName);
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void zipFolder(File folder, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zipFolderRecursive(folder, folder.getName(), zos);
        }
    }

    private void zipFolderRecursive(File folder, String parent, ZipOutputStream zos) throws IOException {
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) {
                zipFolderRecursive(file, parent + "/" + file.getName(), zos);
            } else {
                ZipEntry zipEntry = new ZipEntry(parent + "/" + file.getName());
                zos.putNextEntry(zipEntry);
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    private void setupSystemTray() {
        if (SystemTray.isSupported()) {
            try {
                SystemTray tray = SystemTray.getSystemTray();
                BufferedImage image = ImageIO.read(new File("icon.png"));
                trayIcon = new TrayIcon(image, getResourceString("system_tray_title"));
                trayIcon.setImageAutoSize(true);
                tray.add(trayIcon);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void notify(String message) {
        Platform.runLater(() -> {
            chatArea.appendText(getResourceString("notification") + message + "\n");
            if (trayIcon != null) {
                trayIcon.displayMessage(getResourceString("system_tray_title"), message, TrayIcon.MessageType.INFO);
            }
        });
        sendGroupChat(getResourceString("notification") + message);
    }

    private void setupMediaPlayer() {
        try {
            Media sound = new Media(new File("notification.wav").toURI().toString());
            mediaPlayer = new MediaPlayer(sound);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void playNotificationSound() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.play();
        }
    }

    private void initDatabases() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String sql = "CREATE TABLE IF NOT EXISTS transfers (id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT, type TEXT, size LONG, timestamp TEXT)";
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
    }

    private void logTransfer(String fileName, String type, long size) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String sql = "INSERT INTO transfers (file_name, type, size, timestamp) VALUES (?, ?, ?, ?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, fileName);
            pstmt.setString(2, type);
            pstmt.setLong(3, size);
            pstmt.setString(4, new java.util.Date().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void logChat(String uuid, String message, String type) {
        try (Connection conn = DriverManager.getConnection(CHAT_DB_URL)) {
            String sql = "INSERT INTO chats (uuid, message, type, timestamp) VALUES (?, ?, ?, ?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, uuid);
            pstmt.setString(2, message);
            pstmt.setString(3, type);
            pstmt.setString(4, new java.util.Date().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void logTags(String fileName, String tags) {
        try (Connection conn = DriverManager.getConnection(TAG_DB_URL)) {
            String sql = "INSERT INTO tags (file_name, tags, timestamp) VALUES (?, ?, ?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, fileName);
            pstmt.setString(2, tags);
            pstmt.setString(3, new java.util.Date().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showTransferLog() {
        ListView<String> logView = new ListView<>();
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM transfers");
            while (rs.next()) {
                logView.getItems().add(String.format("%s: %s (%d bytes) at %s",
                        rs.getString("type"), rs.getString("file_name"), rs.getLong("size"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        Stage logStage = new Stage();
        logStage.setTitle(getResourceString("transfer_log"));
        logStage.setScene(new Scene(new VBox(new Label(getResourceString("transfer_log")), logView), 400, 300));
        logStage.show();
    }

    private void showChatLog() {
        ListView<String> logView = new ListView<>();
        try (Connection conn = DriverManager.getConnection(CHAT_DB_URL)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM chats");
            while (rs.next()) {
                logView.getItems().add(String.format("%s: %s (%s) at %s",
                        rs.getString("type"), rs.getString("message"), rs.getString("uuid"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        Stage logStage = new Stage();
        logStage.setTitle(getResourceString("chat_log"));
        logStage.setScene(new Scene(new VBox(new Label(getResourceString("chat_log")), logView), 400, 300));
        logStage.show();
    }

    private void blockUser() {
        String target = deviceListView.getSelectionModel().getSelectedItem();
        if (target == null) {
            notify(getResourceString("select_device"));
            return;
        }
        String uuid = target.split("_")[1];
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(getResourceString("block_user"));
        dialog.setHeaderText(getResourceString("configure_block") + target);
        CheckBox fileBlock = new CheckBox(getResourceString("block_files"));
        CheckBox messageBlock = new CheckBox(getResourceString("block_messages"));
        dialog.getDialogPane().setContent(new VBox(10, fileBlock, messageBlock));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(type -> {
            if (type == ButtonType.OK) {
                userFileBlock.put(uuid, fileBlock.isSelected());
                userMessageBlock.put(uuid, messageBlock.isSelected());
                try (Connection conn = DriverManager.getConnection(USERBOOK_DB_URL)) {
                    String sql = "INSERT OR REPLACE INTO userbook (uuid, file_block, message_block) VALUES (?, ?, ?)";
                    PreparedStatement pstmt = conn.prepareStatement(sql);
                    pstmt.setString(1, uuid);
                    pstmt.setBoolean(2, fileBlock.isSelected());
                    pstmt.setBoolean(3, messageBlock.isSelected());
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                notify(getResourceString("user_blocked") + target);
            }
        });
    }

    private void manageGroups() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(getResourceString("manage_groups"));
        dialog.setHeaderText(getResourceString("create_group"));
        TextField groupNameField = new TextField();
        ListView<String> groupMembers = new ListView<>();
        groupMembers.getItems().addAll(deviceListView.getItems());
        groupMembers.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        dialog.getDialogPane().setContent(new VBox(10, new Label(getResourceString("group_name")), groupNameField,
                new Label(getResourceString("group_members")), groupMembers));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(type -> {
            if (type == ButtonType.OK) {
                String groupName = groupNameField.getText().trim();
                if (!groupName.isEmpty()) {
                    String members = String.join(",", groupMembers.getSelectionModel().getSelectedItems());
                    notify(getResourceString("group_created") + groupName + " (" + members + ")");
                    sendGroupChat(getResourceString("group_message") + groupName);
                }
            }
        });
    }

    private void viewTags() {
        ListView<String> tagView = new ListView<>();
        try (Connection conn = DriverManager.getConnection(TAG_DB_URL)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM tags");
            while (rs.next()) {
                tagView.getItems().add(String.format("%s: %s at %s",
                        rs.getString("file_name"), rs.getString("tags"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        Stage tagStage = new Stage();
        tagStage.setTitle(getResourceString("file_tags"));
        tagStage.setScene(new Scene(new VBox(new Label(getResourceString("file_tags")), tagView), 400, 300));
        tagStage.show();
    }

    private void showStats() {
        PieChart chart = new PieChart();
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT type, COUNT(*) as count FROM transfers GROUP BY type");
            while (rs.next()) {
                chart.getData().add(new PieChart.Data(rs.getString("type"), rs.getInt("count")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try (Connection conn = DriverManager.getConnection(CHAT_DB_URL)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT type, COUNT(*) as count FROM chats GROUP BY type");
            while (rs.next()) {
                chart.getData().add(new PieChart.Data("Chat " + rs.getString("type"), rs.getInt("count")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        Stage statsStage = new Stage();
        statsStage.setTitle(getResourceString("activity_stats"));
        statsStage.setScene(new Scene(new VBox(new Label(getResourceString("activity_stats")), chart), 400, 300));
        statsStage.show();
    }

    private void runNetworkDiagnostics() {
        StringBuilder diagnostics = new StringBuilder();
        diagnostics.append(getResourceString("network_diagnostics") + ":\n");
        NetworkIF[] interfaces = systemInfo.getHardware().getNetworkIFs();
        for (NetworkIF nif : interfaces) {
            diagnostics.append("Interface: ").append(nif.getName()).append("\n");
            diagnostics.append("Bytes Sent: ").append(nif.getBytesSent()).append("\n");
            diagnostics.append("Bytes Received: ").append(nif.getBytesRecv()).append("\n");
        }
        diagnostics.append("CPU Usage: ").append(systemInfo.getHardware().getProcessor().getSystemCpuLoad(1000) * 100).append("%\n");
        Platform.runLater(() -> {
            TextArea diagArea = new TextArea(diagnostics.toString());
            diagArea.setEditable(false);
            Stage diagStage = new Stage();
            diagStage.setTitle(getResourceString("network_diagnostics"));
            diagStage.setScene(new Scene(new VBox(new Label(getResourceString("network_diagnostics")), diagArea), 400, 300));
            diagStage.show();
        });
    }

    private void startAutoBackup() {
        new Thread(() -> {
            while (true) {
                try {
                    String backupPath = savePath + "/backup_" + System.currentTimeMillis() + ".csv";
                    try (CSVPrinter printer = new CSVPrinter(new FileWriter(backupPath), CSVFormat.DEFAULT)) {
                        printer.printRecord("ID", "File Name", "Type", "Size", "Timestamp");
                        try (Connection conn = DriverManager.getConnection(DB_URL)) {
                            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM transfers");
                            while (rs.next()) {
                                printer.printRecord(rs.getInt("id"), rs.getString("file_name"),
                                        rs.getString("type"), rs.getLong("size"), rs.getString("timestamp"));
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
                    Platform.runLater(() -> notify(getResourceString("auto_backup_completed") + backupPath));
                    Thread.sleep(3600000); // 1시간마다 백업
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void addManualDevice() {
        String ip = manualIpInput.getText().trim();
        if (!ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            notify(getResourceString("invalid_ip"));
            return;
        }
        String name = "Manual_" + ip.replace(".", "_");
        discoveredDevices.put(name, ip);
        deviceStatus.put(name, getResourceString("online"));
        deviceListView.getItems().add(name);
        notify(getResourceString("manual_device_added")(mac) + ip);
    }

    private void checkDeviceStatus(String address, String name) {
        new Thread(() -> {
            while (true) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, PORT), 2000);
                    try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                        dos.writeUTF(userUUID);
                        dos.writeUTF("STATUS");
                        dos.writeUTF(userName + "_" + userUUID);
                    }
                    Platform.runLater(() -> deviceStatus.put(name, getResourceString("online")));
                } catch (IOException e) {
                    Platform.runLater(() -> deviceStatus.put(name, getResourceString("offline")));
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private boolean validateUUID(String uuid) {
        return uuid != null && uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private void sendGroupChat(String message) {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            byte[] buffer = (userName + "_" + userUUID + ": " + message).getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Map<String, String> getDiscoveredDevices() {
        return discoveredDevices;
    }

    public static void updateUserName(String newName) {
        userName = newName;
    }

    public static void updateAvatarPath(String path) {
        avatarPath = path;
        updateAvatar();
    }

    private static void updateAvatar() {
        if (!avatarPath.isEmpty()) {
            try {
                Image image = new Image(new File(avatarPath).toURI().toString());
                avatarView.setImage(image);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void setSavePath(String path) {
        savePath = path;
    }
}