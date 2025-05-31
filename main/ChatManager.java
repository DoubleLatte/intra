package filesharing.main;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class ChatManager {
    private static final int MULTICAST_PORT = 4446;
    private static final String MULTICAST_ADDRESS = "239.255.0.1";
    private static final int MAX_RETRIES = 3;
    private static Map<String, String> emojiMap = loadEmojiMap();
    private static MediaPlayer mediaPlayer;
    private final DeviceManager deviceManager;
    private final DatabaseManager databaseManager;
    private final SecurityManager securityManager;
    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(2);

    public ChatManager(DeviceManager deviceManager, DatabaseManager databaseManager, SecurityManager securityManager) {
        this.deviceManager = deviceManager;
        this.databaseManager = databaseManager;
        this.securityManager = securityManager;
    }

    public void sendChat(String target, String message, TextArea chatArea, TextField chatInput) {
        if (target == null || message.isEmpty()) return;

        String address = deviceManager.getDiscoveredDevices().get(target);
        if (address == null) {
            notify(getResourceString("device_not_found"));
            return;
        }

        chatExecutor.submit(() -> {
            int attempt = 0;
            boolean success = false;
            while (attempt < MAX_RETRIES && !success) {
                try (var socket = securityManager.createSSLSocket(address, 12345)) {
                    socket.startHandshake();
                    try (var dos = new DataOutputStream(socket.getOutputStream())) {
                        dos.writeUTF(deviceManager.getUserUUID());
                        dos.writeUTF("CHAT");
                        dos.writeUTF(securityManager.encryptMessage(message));
                        databaseManager.logChat(deviceManager.getUserUUID(), message, "전송");
                        Platform.runLater(() -> chatArea.appendText(getResourceString("sent") + processMessage(message) + "\n"));
                        success = true;
                    }
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
        Platform.runLater(chatInput::clear);
    }

    public void startMulticastListener() {
        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.joinGroup(group);
            byte[] buffer = new byte[8192];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String encrypted = new String(packet.getData(), 0, packet.getLength());
                String message = securityManager.decryptMessage(encrypted);
                String senderUUID = message.split(":")[0].split("_")[1].trim();
                if (!databaseManager.isMessageBlocked(senderUUID)) {
                    databaseManager.logChat(senderUUID, message, "수신");
                    Platform.runLater(() -> notify(getResourceString("group") + processMessage(message)));
                    playNotificationSound();
                }
            }
        } catch (IOException e) {
            Platform.runLater(() -> notify("Multicast error: " + e.getMessage()));
        }
    }

    public void sendGroupChat(String message) {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            byte[] buffer = securityManager.encryptMessage(deviceManager.getUserName() + "_" + deviceManager.getUserUUID() + ": " + message).getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            socket.send(packet);
        } catch (IOException e) {
            Platform.runLater(() -> notify("Group chat error: " + e.getMessage()));
        }
    }

    public void searchChatLog(String keyword, ListView<String> logView) {
        logView.getItems().clear();
        try (var conn = databaseManager.getChatConnection()) {
            String sql = "SELECT * FROM chats WHERE message LIKE ?";
            try (var pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, "%" + keyword + "%");
                var rs = pstmt.executeQuery();
                while (rs.next()) {
                    logView.getItems().add(String.format("%s: %s (%s) at %s",
                            rs.getString("type"), rs.getString("message"), rs.getString("uuid"), rs.getString("timestamp")));
                }
            }
        } catch (SQLException e) {
            Platform.runLater(() -> notify("Chat search error: " + e.getMessage()));
        }
    }

    public void setupMediaPlayer() {
        try {
            Media sound = new Media(new File("notification.wav").toURI().toString());
            mediaPlayer = new MediaPlayer(sound);
        } catch (Exception e) {
            Platform.runLater(() -> notify("Media player setup error: " + e.getMessage()));
        }
    }

    public void playNotificationSound() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.play();
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

    private String getResourceString(String key) {
        return java.util.ResourceBundle.getBundle("messages", java.util.Locale.getDefault()).getString(key);
    }

    private void notify(String message) {
        Platform.runLater(() -> System.out.println(message)); // UIManager로 전달
    }
}