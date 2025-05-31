import javafx.application.Platform;
import javafx.scene.control.*;
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

public class MainWindow {
    private static final String SERVICE_TYPE = "_fileshare._tcp.local.";
    private static final int PORT = 12345;
    private static final int MULTICAST_PORT = 4446;
    private static final String MULTICAST_ADDRESS = "239.255.0.1";
    private static final String DB_URL = "jdbc:sqlite:transfer_log.db";
    private static final String CHAT_DB_URL = "jdbc:sqlite:chat_log.db";
    private static JmDNS jmdns;
    private static Map<String, String> discoveredDevices = new HashMap<>();
    private static Map<String, String> deviceStatus = new HashMap<>();
    private static Set<String> blockedUUIDs = new HashSet<>();
    private static String userUUID = UUID.randomUUID().toString();
    private static String userName = "User_" + userUUID.substring(0, 8);
    private static boolean autoAcceptFiles = false;
    private static long transferSpeedLimit = 0; // 바이트/초, 0은 무제한
    private ListView<String> deviceListView;
    private TextArea chatArea;
    private TextField chatInput;
    private ProgressBar progressBar;
    private TrayIcon trayIcon;
    private TextField manualIpInput;

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
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        manualIpInput = new TextField();
        manualIpInput.setPromptText(getResourceString("manual_ip_prompt"));
        Button addManualIpButton = new Button(getResourceString("add_manual_ip"));

        VBox controls = new VBox(10, new Label(getResourceString("device_list")), deviceListView,
                new Label(getResourceString("chat")), chatArea, chatInput, sendChatButton,
                sendFileButton, autoAcceptCheckBox, viewLogButton, viewChatLogButton,
                blockUserButton, new Label(getResourceString("manual_ip")), manualIpInput,
                addManualIpButton, progressBar);
        tab.setContent(controls);

        sendChatButton.setOnAction(e -> sendChat());
        sendFileButton.setOnAction(e -> sendFileOrFolder());
        autoAcceptCheckBox.setOnAction(e -> autoAcceptFiles = autoAcceptCheckBox.isSelected());
        viewLogButton.setOnAction(e -> showTransferLog());
        viewChatLogButton.setOnAction(e -> showChatLog());
        blockUserButton.setOnAction(e -> blockUser());
        addManualIpButton.setOnAction(e -> addManualDevice());

        try {
            setupMDNS();
            new Thread(this::startServer).start();
            new Thread(this::startMulticastListener).start();
            setupSystemTray();
            initDatabases();
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

            if (type.equals("CHAT")) {
                String message = dis.readUTF();
                logChat(uuid, message, "수신");
                Platform.runLater(() -> chatArea.appendText(getResourceString("received") + message + "\n"));
            } else if (type.equals("FILE")) {
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                String hash = dis.readUTF();
                Platform.runLater(() -> {
                    if (autoAcceptFiles) {
                        receiveFile(fileName, fileSize, hash, dis);
                    } else {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                                getResourceString("file_receive_prompt") + fileName + " (" + fileSize + " bytes)?");
                        alert.showAndWait().ifPresent(response -> {
                            if (response.getButtonData().isDefaultButton()) {
                                receiveFile(fileName, fileSize, hash, dis);
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

    private void receiveFile(String fileName, long fileSize, String expectedHash, DataInputStream dis) {
        try {
            FileOutputStream fos = new FileOutputStream("received_" + fileName);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[4096];
            long bytesRead = 0;
            int count;
            long startTime = System.currentTimeMillis();
            while (bytesRead < fileSize && (count = dis.read(buffer)) > 0) {
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
            Platform.runLater(() -> {
                progressBar.setProgress(0);
                progressBar.setVisible(false);
                notify(getResourceString("file_received") + fileName);
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
                logChat("GROUP", message, "수신");
                Platform.runLater(() -> chatArea.appendText(getResourceString("group") + message + "\n"));
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
                byte[] buffer = new byte[4096];
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

                byte[] buffer = new byte[4096];
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
        blockedUUIDs.add(uuid);
        notify(getResourceString("user_blocked") + target);
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
}