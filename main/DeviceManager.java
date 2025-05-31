package filesharing.main;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javax.net.ssl.SSLSocket;

public class DeviceManager {
    private static final String SERVICE_TYPE = "_fileshare._tcp.local.";
    private static final int PORT = 12345;
    private static JmDNS jmdns;
    private static Map<String, String> discoveredDevices = new ConcurrentHashMap<>();
    private static Map<String, String> deviceStatus = new ConcurrentHashMap<>();
    private static Map<String, String> userStatuses = new ConcurrentHashMap<>();
    private static String userUUID = UUID.randomUUID().toString();
    private static String userName = "User_" + userUUID.substring(0, 8);
    private static String userStatus = "Online";
    private final SecurityManager securityManager;

    public DeviceManager() {
        this.securityManager = new SecurityManager();
    }

    public void setupMDNS() throws IOException {
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
                    userStatuses.remove(name);
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
                    userStatuses.put(name, "Online");
                    notify(getResourceString("device_info") + name + " (" + address + ")");
                });
                checkDeviceStatus(address, name);
            }
        });

        jmdns.registerService(javax.jmdns.ServiceInfo.create(
                SERVICE_TYPE, userName + "_" + userUUID, PORT, "SSL: true"));
    }

    public void startServer() {
        try (var serverSocket = securityManager.createSSLServerSocket(PORT)) {
            serverSocket.setNeedClientAuth(true);
            while (true) {
                var socket = serverSocket.accept();
                new Thread(() -> handleClient(socket)).start();
            }
        } catch (IOException e) {
            Platform.runLater(() -> notify("Server error: " + e.getMessage()));
        }
    }

    private void handleClient(Socket socket) {
        try (var dis = new DataInputStream(socket.getInputStream());
             var dos = new DataOutputStream(socket.getOutputStream())) {
            String uuid = dis.readUTF();
            if (!securityManager.validateUUID(uuid)) {
                socket.close();
                return;
            }
            String type = dis.readUTF();
            if (type.equals("STATUS")) {
                String name = dis.readUTF();
                String status = dis.readUTF();
                Platform.runLater(() -> {
                    deviceStatus.put(name, getResourceString("online"));
                    userStatuses.put(name, status);
                });
            }
        } catch (IOException e) {
            // Handled by ChatManager or FileTransferManager
        }
    }

    public void addManualDevice(String ip, Runnable notifyCallback) {
        if (!ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            notifyCallback.run();
            return;
        }
        String name = "Manual_" + ip.replace(".", "_");
        discoveredDevices.put(name, ip);
        deviceStatus.put(name, getResourceString("online"));
        userStatuses.put(name, "Online");
        notifyCallback.run();
    }

    private void checkDeviceStatus(String address, String name) {
        new Thread(() -> {
            while (true) {
                try (SSLSocket socket = securityManager.createSSLSocket()) {
                    socket.connect(new InetSocketAddress(address, PORT), 2000);
                    socket.startHandshake();
                    try (var dos = new DataOutputStream(socket.getOutputStream())) {
                        dos.writeUTF(userUUID);
                        dos.writeUTF("STATUS");
                        dos.writeUTF(userName + "_" + userUUID);
                        dos.writeUTF(userStatus);
                    }
                    Platform.runLater(() -> {
                        deviceStatus.put(name, getResourceString("online"));
                        userStatuses.put(name, userStatuses.getOrDefault(name, "Online"));
                    });
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

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void notify(String message) {
        Platform.runLater(() -> System.out.println(message)); // UIManager로 전달
    }

    public Map<String, String> getDiscoveredDevices() {
        return discoveredDevices;
    }

    public Map<String, String> getDeviceStatus() {
        return deviceStatus;
    }

    public Map<String, String> getUserStatuses() {
        return userStatuses;
    }

    public String getUserUUID() {
        return userUUID;
    }

    public String getUserName() {
        return userName;
    }

    public void updateUserName(String newName) {
        userName = newName;
    }

    public void setUserStatus(String status) {
        userStatus = status;
        broadcastStatus();
    }

    private void broadcastStatus() {
        discoveredDevices.forEach((name, address) -> {
            try (var socket = securityManager.createSSLSocket(address, PORT)) {
                socket.startHandshake();
                try (var dos = new DataOutputStream(socket.getOutputStream())) {
                    dos.writeUTF(userUUID);
                    dos.writeUTF("STATUS");
                    dos.writeUTF(userName + "_" + userUUID);
                    dos.writeUTF(userStatus);
                }
            } catch (IOException e) {
                // Silent
            }
        });
    }
}