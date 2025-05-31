package filesharing.main;

import javafx.application.Platform;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.PopupMenu;
import java.awt.MenuItem;
import java.awt.image.BufferedImage;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.net.ssl.*;
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
    private static final String CONTACT_DB_URL = "jdbc:sqlite:contact.db";
    private static JmDNS jmdns;
    private static Map<String, String> discoveredDevices = new ConcurrentHashMap<>();
    private static Map<String, String> deviceStatus = new ConcurrentHashMap<>();
    private static Set<String> blockedUUIDs = ConcurrentHashMap.newKeySet();
    private static Map<String, Boolean> userFileBlock = new ConcurrentHashMap<>();
    private static Map<String, Boolean> userMessageBlock = new ConcurrentHashMap<>();
    private static Map<String, String> contactGrades = new ConcurrentHashMap<>();
    private static String userUUID = UUID.randomUUID().toString();
    private static String userName = "User_" + userUUID.substring(0, 8);
    private static String avatarPath = "";
    private static boolean autoAcceptFiles = false;
    private static long transferSpeedLimit = 0;
    private static String savePath = System.getProperty("user.home") + "/Downloads";
    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_RETRIES = 3;
    private static ExecutorService transferExecutor = Executors.newFixedThreadPool(4);
    private static Map<String, String> emojiMap = loadEmojiMap();
    private static SSLContext sslContext;
    private static KeyManagerFactory kmf;
    private static TrustManagerFactory tmf;
    private static SecretKeySpec aesKey;
    private static Map<String, Double> transferProgress = new ConcurrentHashMap<>();
    private ListView<String> deviceListView;
    private TextArea chatArea;
    private TextField chatInput;
    private ProgressBar progressBar;
    private TrayIcon trayIcon;
    private TextField manualIpInput;
    private ImageView avatarView;
    private static SystemInfo systemInfo = new SystemInfo();
    private static MediaPlayer mediaPlayer;

    static {
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream("keystore.jks"), "password".toCharArray());
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "password".toCharArray());
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            aesKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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
        Button previewFileButton = new Button(getResourceString("preview_file"));
        Button manageContactsButton = new Button(getResourceString("manage_contacts"));
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
                networkDiagnosticsButton, previewFileButton, manageContactsButton,
                new Label(getResourceString("manual_ip")), manualIpInput, addManualIpButton,
                progressBar, new Label(getResourceString("avatar")), avatarView);
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
        previewFileButton.setOnAction(e -> previewFile());
        manageContactsButton.setOnAction(e -> manageContacts());
        addManualIpButton.setOnAction(e -> addManualDevice());
        startAutoBackup();
        startLogCleanup();

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
                SERVICE_TYPE, userName + "_" + userUUID, PORT, "SSL: true"));
    }

    private void startServer() {
        try (SSLServerSocket serverSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(PORT)) {
            serverSocket.setNeedClientAuth(true);
            while (true) {
                Socket socket = serverSocket.accept();
                transferExecutor.submit(() -> handleClient(socket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket socket) {
        try {
            SSLSocket sslSocket = (SSLSocket) socket;
            sslSocket.startHandshake();
            DataInputStream dis = new DataInputStream(sslSocket.getInputStream());
            String uuid = dis.readUTF();
            if (!validateUUID(uuid) || blockedUUIDs.contains(uuid)) {
                socket.close();
                return;
            }

            String type = dis.readUTF();
            if (type.equals("CHAT") && !userMessageBlock.getOrDefault(uuid, false)) {
                String encryptedMessage = dis.readUTF();
                String message = decryptMessage(encryptedMessage);
                logChat(uuid, message, "수신");
                Platform.runLater(() -> chatArea.appendText(getResourceString("received") + processMessage(message) + "\n"));
                playNotificationSound();
            } else if (type.equals("FILE") && !userFileBlock.getOrDefault(uuid, false)) {
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                String metadata = dis.readUTF();
                String hash = dis.readUTF();
                String tags = dis.readUTF();
                transferProgress.put(fileName, 0.0);
                Platform.runLater(() -> {
                    if (autoAcceptFiles) {
                        receiveFileWithRetry(fileName, fileSize, metadata, hash, tags, sslSocket, dis);
                    } else {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                                getResourceString("file_receive_prompt") + fileName + " (" + fileSize + " bytes)\n" + metadata);
                        alert.showAndWait().ifPresent(response -> {
                            if (response.getButtonData().isDefaultButton()) {
                                receiveFileWithRetry(fileName, fileSize, metadata, hash, tags, sslSocket, dis);
                            }
                        });
                    }
                });
            } else if (type.equals("STATUS")) {
                String name = dis.readUTF();
                Platform.runLater(() -> deviceStatus.put(name, getResourceString("online")));
            } else if (type.equals("VERSION")) {
                String version = dis.readUTF();
                String devName = dis.readUTF();
                if (!devName.equals("MainDeveloper")) {
                    Platform.runLater(() -> notify(getResourceString("third_party_warning")));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void receiveFileWithRetry(String fileName, long fileSize, String metadata, String expectedHash, String tags, Socket socket, DataInputStream dis) {
        int attempt = 0;
        boolean success = false;
        while (attempt < MAX_RETRIES && !success) {
            try {
                attempt++;
                receiveFile(fileName, fileSize, metadata, expectedHash, tags, dis);
                success = true;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    Platform.runLater(() -> notify(getResourceString("transfer_failed")));
                    transferProgress.remove(fileName);
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    private void receiveFile(String fileName, long fileSize, String metadata, String expectedHash, String tags, DataInputStream dis) throws Exception {
        File saveDir = new File(savePath);
        if (!saveDir.exists()) saveDir.mkdirs();
        File outputFile = new File(saveDir, fileName);
        FileOutputStream fos = new FileOutputStream(outputFile);
        FileChannel outChannel = fos.getChannel();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
        long bytesRead = 0;
        long startTime = System.currentTimeMillis();
        while (bytesRead < fileSize) {
            int read = dis.read(buffer.array(), 0, Math.min(CHUNK_SIZE, (int)(fileSize - bytesRead)));
            if (read == -1) break;
            buffer.limit(read);
            outChannel.write(buffer);
            digest.update(buffer.array(), 0, read);
            bytesRead += read;
            buffer.clear();
            double progress = (double) bytesRead / fileSize;
            transferProgress.put(fileName, progress);
            updateTrayMenu();
            Platform.runLater(() -> progressBar.setProgress(progress));
            if (transferSpeedLimit > 0) {
                throttleTransfer(bytesRead, startTime);
            }
        }
        outChannel.close();
        fos.close();
        String receivedHash = bytesToHex(digest.digest());
        if (!receivedHash.equals(expectedHash)) {
            Platform.runLater(() -> notify(getResourceString("file_integrity_failed")));
            outputFile.delete();
            throw new IOException("Integrity check failed");
        }
        logTransfer(fileName, "수신", fileSize, metadata);
        logTags(fileName, tags);
        transferProgress.remove(fileName);
        updateTrayMenu();
        Platform.runLater(() -> {
            progressBar.setProgress(0);
            progressBar.setVisible(false);
            notify(getResourceString("file_received") + fileName);
            playNotificationSound();
        });
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

    public static String bytesToHex(byte[] bytes) {
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

        transferExecutor.submit(() -> {
            int attempt = 0;
            boolean success = false;
            while (attempt < MAX_RETRIES && !success) {
                try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket(address, PORT)) {
                    socket.startHandshake();
                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                    dos.writeUTF(userUUID);
                    dos.writeUTF("CHAT");
                    dos.writeUTF(encryptMessage(message));
                    logChat(userUUID, message, "전송");
                    Platform.runLater(() -> chatArea.appendText(getResourceString("sent") + processMessage(message) + "\n"));
                    success = true;
                } catch (Exception e) {
                    attempt++;
                    if (attempt == MAX_RETRIES) {
                        Platform.runLater(() -> notify(getResourceString("transfer_failed")));
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });
        chatInput.clear();
    }

    private void startMulticastListener() {
        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.joinGroup(group);
            byte[] buffer = new byte[8192];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String encrypted = new String(packet.getData(), 0, packet.getLength());
                String message = decryptMessage(encrypted);
                String senderUUID = message.split(":")[0].split("_")[1].trim();
                if (!userMessageBlock.getOrDefault(senderUUID, false)) {
                    logChat(senderUUID, message, "수신");
                    Platform.runLater(() -> chatArea.appendText(getResourceString("group") + processMessage(message) + "\n"));
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
        List<File> files = fileChooser.showOpenMultipleDialog(null);
        if (files == null || files.isEmpty()) return;

        files.sort(Comparator.comparingLong(File::length));

        for (File file : files) {
            String fileName = file.getName();
            transferProgress.put(fileName, 0.0);
            transferExecutor.submit(() -> sendFile(file, address, tags.orElse("")));
        }
    }

    private void sendFile(File file, String address, String tags) {
        int attempt = 0;
        boolean success = false;
        while (attempt < MAX_RETRIES && !success) {
            try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket(address, PORT)) {
                socket.startHandshake();
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                FileInputStream fis = new FileInputStream(file);
                FileChannel inChannel = fis.getChannel();

                String fileName = file.getName();
                File sendFile = file;
                if (file.isDirectory()) {
                    fileName = file.getName() + ".zip";
                    sendFile = new File(savePath, fileName);
                    zipFolder(file, sendFile);
                }

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileInputStream hashFis = new FileInputStream(sendFile)) {
                    byte[] buffer = new byte[CHUNK_SIZE];
                    int count;
                    while ((count = hashFis.read(buffer)) > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
                String fileHash = bytesToHex(digest.digest());
                String metadata = String.format("Size: %d bytes, Modified: %s", sendFile.length(), new Date(sendFile.lastModified()));

                dos.writeUTF(userUUID);
                dos.writeUTF("FILE");
                dos.writeUTF(fileName);
                dos.writeLong(sendFile.length());
                dos.writeUTF(metadata);
                dos.writeUTF(fileHash);
                dos.writeUTF(tags);

                ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
                long bytesRead = 0;
                long startTime = System.currentTimeMillis();
                progressBar.setVisible(true);
                while (bytesRead < sendFile.length()) {
                    int read = inChannel.read(buffer);
                    if (read == -1) break;
                    buffer.flip();
                    dos.write(buffer.array(), 0, read);
                    bytesRead += read;
                    buffer.clear();
                    double progress = (double) bytesRead / sendFile.length();
                    transferProgress.put(fileName, progress);
                    updateTrayMenu();
                    Platform.runLater(() -> progressBar.setProgress(progress));
                    if (transferSpeedLimit > 0) {
                        throttleTransfer(bytesRead, startTime);
                    }
                }
                inChannel.close();
                fis.close();
                logTransfer(fileName, "전송", sendFile.length(), metadata);
                logTags(fileName, tags);
                transferProgress.remove(fileName);
                updateTrayMenu();
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    notify(getResourceString("file_sent") + fileName);
                });
                success = true;
                if (sendFile != file) sendFile.delete();
            } catch (Exception e) {
                attempt++;
                if (attempt == MAX_RETRIES) {
                    Platform.runLater(() -> notify(getResourceString("transfer_failed")));
                    transferProgress.remove(file.getName());
                    updateTrayMenu();
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
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

                PopupMenu popup = new PopupMenu();
                MenuItem showItem = new MenuItem(getResourceString("show_app"));
                MenuItem exitItem = new MenuItem(getResourceString("exit"));
                Menu transfersMenu = new Menu(getResourceString("transfers"));
                popup.add(showItem);
                popup.add(transfersMenu);
                popup.add(exitItem);
                trayIcon.setPopupMenu(popup);

                showItem.addActionListener(e -> Platform.runLater(() -> App.getInstance().getPrimaryStage().show()));
                exitItem.addActionListener(e -> {
                    transferExecutor.shutdown();
                    Platform.exit();
                    tray.remove(trayIcon);
                });

                tray.add(trayIcon);
                updateTrayMenu();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void updateTrayMenu() {
        if (trayIcon == null) return;
        PopupMenu popup = trayIcon.getPopupMenu();
        Menu transfersMenu = (Menu) popup.getItem(1);
        transfersMenu.removeAll();
        transferProgress.forEach((fileName, progress) -> {
            MenuItem item = new MenuItem(String.format("%s: %.0f%%", fileName, progress * 100));
            item.setEnabled(false);
            transfersMenu.add(item);
        });
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
    }

    private void logTransfer(String fileName, String type, long size, String metadata) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String sql = "INSERT INTO transfers (file_name, type, size, metadata, timestamp) VALUES (?, ?, ?, ?, ?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, fileName);
            pstmt.setString(2, type);
            pstmt.setLong(3, size);
            pstmt.setString(4, metadata);
            pstmt.setString(5, new java.util.Date().toString());
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
                logView.getItems().add(String.format("%s: %s (%d bytes) at %s\n%s",
                        rs.getString("type"), rs.getString("file_name"), rs.getLong("size"),
                        rs.getString("timestamp"), rs.getString("metadata")));
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
        diagnostics.append(getResourceString("network_diagnostics")).append(":\n");
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

    private void previewFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg"),
                new FileChooser.ExtensionFilter("Text", "*.txt"));
        File file = fileChooser.showOpenDialog(null);
        if (file == null) return;

        Stage previewStage = new Stage();
        previewStage.setTitle(getResourceString("file_preview"));
        if (file.getName().endsWith(".png") || file.getName().endsWith(".jpg")) {
            Image image = new Image(file.toURI().toString());
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(400);
            imageView.setPreserveRatio(true);
            previewStage.setScene(new Scene(new VBox(new Label(file.getName()), imageView)));
        } else if (file.getName().endsWith(".txt")) {
            WebView webView = new WebView();
            try {
                String content = Files.readString(file.toPath());
                webView.getEngine().loadContent("<pre>" + content + "</pre>");
            } catch (IOException e) {
                e.printStackTrace();
            }
            previewStage.setScene(new Scene(new VBox(new Label(file.getName()), webView), 400, 300));
        }
        previewStage.show();
    }

    private void manageContacts() {
        String target = deviceListView.getSelectionModel().getSelectedItem();
        if (target == null) {
            notify(getResourceString("select_device"));
            return;
        }
        String uuid = target.split("_")[1];
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(getResourceString("manage_contacts"));
        dialog.setHeaderText(getResourceString("set_contact_grade") + target);
        ComboBox<String> gradeCombo = new ComboBox<>();
        gradeCombo.getItems().addAll("Green", "Orange", "Red");
        gradeCombo.setValue(contactGrades.getOrDefault(uuid, "Green"));
        dialog.getDialogPane().setContent(new VBox(10, new Label(getResourceString("contact_grade")), gradeCombo));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(type -> {
            if (type == ButtonType.OK) {
                String grade = gradeCombo.getValue();
                contactGrades.put(uuid, grade);
                try (Connection conn = DriverManager.getConnection(CONTACT_DB_URL)) {
                    String sql = "INSERT OR REPLACE INTO contacts (uuid, grade) VALUES (?, ?)";
                    PreparedStatement pstmt = conn.prepareStatement(sql);
                    pstmt.setString(1, uuid);
                    pstmt.setString(2, grade);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                notify(getResourceString("contact_updated") + target + " (" + grade + ")");
            }
        });
    }

    private void startAutoBackup() {
        new Thread(() -> {
            while (true) {
                try {
                    String backupPath = savePath + "/backup_" + System.currentTimeMillis() + ".csv";
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
                    Platform.runLater(() -> notify(getResourceString("auto_backup_completed") + backupPath));
                    Thread.sleep(3600000); // 1시간마다
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void startLogCleanup() {
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
                    Thread.sleep(86400000); // 1일마다
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
        notify(getResourceString("manual_device_added") + ip);
    }

    private void checkDeviceStatus(String address, String name) {
        new Thread(() -> {
            while (true) {
                try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
                    socket.connect(new InetSocketAddress(address, PORT), 2000);
                    socket.startHandshake();
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
            byte[] buffer = encryptMessage(userName + "_" + userUUID + ": " + message).getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String encryptMessage(String message) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
            byte[] encrypted = cipher.doFinal(message.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return Base64.getEncoder().encodeToString(message.getBytes());
        }
    }

    private String decryptMessage(String encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, aesKey);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            return new String(decrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return new String(Base64.getDecoder().decode(encrypted));
        }
    }

    private String processMessage(String message) {
        for (Map.Entry<String, String> entry : emojiMap.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }

    private static Map<String, String> loadEmojiMap() {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("emojis.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    map.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return map;
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