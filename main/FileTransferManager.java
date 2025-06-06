package filesharing.main;

import javafx.application.Platform;
import javafx.stage.FileChooser;
import java.io.*;
import java.net.Socket;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import oshi.SystemInfo;
import oshi.hardware.NetworkIF;

public class FileTransferManager {
    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_RETRIES = 3;
    private static String savePath = System.getProperty("user.home") + "/Downloads";
    private static long transferSpeedLimit = 0;
    private static boolean autoAcceptFiles = false;
    private static boolean autoBandwidthLimit = false;
    private final DeviceManager deviceManager;
    private final DatabaseManager databaseManager;
    private final SecurityManager securityManager;
    private final ExecutorService transferExecutor = Executors.newFixedThreadPool(4);
    private final Map<String, Double> transferProgress = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> transferTasks = new ConcurrentHashMap<>();
    private final SystemInfo systemInfo = new SystemInfo();
    private final Map<String, Integer> pendingNotifications = new ConcurrentHashMap<>();

    public FileTransferManager(DeviceManager deviceManager, DatabaseManager databaseManager, SecurityManager securityManager) {
        this.deviceManager = deviceManager;
        this.databaseManager = databaseManager;
        this.securityManager = securityManager;
        if (autoBandwidthLimit) {
            startBandwidthMonitor();
        }
    }

    public void sendFileOrFolder(String target, TextInputDialog tagDialog, ProgressBar progressBar) {
        if (target == null) {
            notify(getResourceString("select_device"));
            return;
        }
        String address = deviceManager.getDiscoveredDevices().get(target);
        if (address == null) {
            notify(getResourceString("device_not_found"));
            return;
        }

        Optional<String> tags = tagDialog.showAndWait();
        FileChooser fileChooser = new FileChooser();
        List<File> files = fileChooser.showOpenMultipleDialog(null);
        if (files == null || files.isEmpty()) return;

        files.sort(Comparator.comparingLong(File::length));

        for (File file : files) {
            String fileName = file.getName();
            transferProgress.put(fileName, 0.0);
            Future<?> task = transferExecutor.submit(() -> sendFile(file, address, tags.orElse(""), progressBar));
            transferTasks.put(fileName, task);
        }
    }

    private void sendFile(File file, String address, String tags, ProgressBar progressBar) {
        int attempt = 0;
        boolean success = false;
        String fileName = file.getName();
        while (attempt < MAX_RETRIES && !success) {
            try (var socket = securityManager.createSSLSocket(address, 12345)) {
                socket.startHandshake();
                try (var dos = new DataOutputStream(socket.getOutputStream());
                     var fis = new FileInputStream(file);
                     var inChannel = fis.getChannel()) {

                    File sendFile = file;
                    if (file.isDirectory()) {
                        fileName = file.getName() + ".zip";
                        sendFile = new File(savePath, fileName);
                        zipFolder(file, sendFile);
                    }

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (var hashFis = new FileInputStream(sendFile)) {
                        byte[] buffer = new byte[CHUNK_SIZE];
                        int count;
                        while ((count = hashFis.read(buffer)) > 0) {
                            digest.update(buffer, 0, count);
                        }
                    }
                    String fileHash = securityManager.bytesToHex(digest.digest());
                    String metadata = String.format("Size: %d bytes, Modified: %s", sendFile.length(), new Date(sendFile.lastModified()));

                    dos.writeUTF(deviceManager.getUserUUID());
                    dos.writeUTF("FILE");
                    dos.writeUTF(fileName);
                    dos.writeLong(sendFile.length());
                    dos.writeUTF(metadata);
                    dos.writeUTF(fileHash);
                    dos.writeUTF(tags);

                    ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
                    long bytesRead = 0;
                    long startTime = System.currentTimeMillis();
                    Platform.runLater(() -> progressBar.setVisible(true));
                    while (bytesRead < sendFile.length()) {
                        int read = inChannel.read(buffer);
                        if (read == -1) break;
                        buffer.flip();
                        dos.write(buffer.array(), 0, read);
                        bytesRead += read;
                        buffer.clear();
                        double progress = (double) bytesRead / sendFile.length();
                        transferProgress.put(fileName, progress);
                        Platform.runLater(() -> progressBar.setProgress(progress));
                        throttleTransfer(bytesRead, startTime);
                    }
                    databaseManager.logTransfer(fileName, "전송", sendFile.length(), metadata);
                    databaseManager.logTags(fileName, tags);
                    databaseManager.logActivity(deviceManager.getUserUUID(), "File sent: " + fileName);
                    transferProgress.remove(fileName);
                    transferTasks.remove(fileName);
                    Platform.runLater(() -> {
                        progressBar.setProgress(0);
                        progressBar.setVisible(false);
                        notify(getResourceString("file_sent") + fileName);
                    });
                    success = true;
                    if (sendFile != file) sendFile.delete();
                }
            } catch (Exception e) {
                attempt++;
                if (attempt == MAX_RETRIES) {
                    Platform.runLater(() -> notify(getResourceString("transfer_failed")));
                    transferProgress.remove(fileName);
                    transferTasks.remove(fileName);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public void receiveFile(String fileName, long fileSize, String metadata, String expectedHash, String tags, Socket socket, DataInputStream dis, ProgressBar progressBar) {
        int attempt = 0;
        boolean success = false;
        while (attempt < MAX_RETRIES && !success) {
            try {
                attempt++;
                receiveFileInternal(fileName, fileSize, metadata, expectedHash, tags, dis, progressBar);
                success = true;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    Platform.runLater(() -> notify(getResourceString("transfer_failed")));
                    transferProgress.remove(fileName);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    private void receiveFileInternal(String fileName, long fileSize, String metadata, String expectedHash, String tags, DataInputStream dis, ProgressBar progressBar) throws Exception {
        File saveDir = new File(savePath);
        if (!saveDir.exists()) saveDir.mkdirs();
        
        // Version Management: Backup existing file
        File outputFile = new File(saveDir, fileName);
        if (outputFile.exists()) {
            String versionedName = fileName + ".v" + System.currentTimeMillis();
            File versionedFile = new File(saveDir, versionedName);
            Files.copy(outputFile.toPath(), versionedFile.toPath());
            databaseManager.logFileVersion(fileName, versionedName, outputFile.length(), expectedHash);
        }

        try (var fos = new FileOutputStream(outputFile);
             var outChannel = fos.getChannel()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
            long bytesRead = 0;
            long startTime = System.currentTimeMillis();
            transferProgress.put(fileName, 0.0);
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
                Platform.runLater(() -> progressBar.setProgress(progress));
                throttleTransfer(bytesRead, startTime);
            }
            String receivedHash = securityManager.bytesToHex(digest.digest());
            if (!receivedHash.equals(expectedHash)) {
                Platform.runLater(() -> notify(getResourceString("file_integrity_failed")));
                outputFile.delete();
                throw new IOException("Integrity check failed");
            }
            databaseManager.logTransfer(fileName, "수신", fileSize, metadata);
            databaseManager.logTags(fileName, tags);
            databaseManager.logDownload(fileName, metadata);
            databaseManager.logActivity(deviceManager.getUserUUID(), "File received: " + fileName);
            pendingNotifications.merge(fileName, 1, Integer::sum);
            transferProgress.remove(fileName);
            Platform.runLater(() -> {
                progressBar.setProgress(0);
                progressBar.setVisible(false);
                notify(getResourceString("file_received") + fileName);
            });
        }
    }

    public void cancelTransfer(String fileName) {
        Future<?> task = transferTasks.remove(fileName);
        if (task != null) {
            task.cancel(true);
            transferProgress.remove(fileName);
            databaseManager.logActivity(deviceManager.getUserUUID(), "Transfer cancelled: " + fileName);
            notify(getResourceString("transfer_cancelled") + fileName);
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

    private void throttleTransfer(long bytesRead, long startTime) {
        long effectiveSpeedLimit = autoBandwidthLimit ? calculateDynamicBandwidthLimit() : transferSpeedLimit;
        if (effectiveSpeedLimit > 0) {
            long elapsed = System.currentTimeMillis() - startTime;
            long expectedTime = (bytesRead * 1000L) / effectiveSpeedLimit;
            if (elapsed < expectedTime) {
                try {
                    Thread.sleep(expectedTime - elapsed);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private long calculateDynamicBandwidthLimit() {
        NetworkIF[] interfaces = systemInfo.getHardware().getNetworkIFs();
        long totalBytesPerSec = 0;
        for (NetworkIF nif : interfaces) {
            long bytesSent = nif.getBytesSent();
            long bytesRecv = nif.getBytesRecv();
            try {
                Thread.sleep(1000);
                nif.updateAttributes();
                long newBytesSent = nif.getBytesSent();
                long newBytesRecv = nif.getBytesRecv();
                totalBytesPerSec += (newBytesSent - bytesSent) + (newBytesRecv - bytesRecv);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return Math.max(1024, totalBytesPerSec / 2);
    }

    private void startBandwidthMonitor() {
        new Thread(() -> {
            while (autoBandwidthLimit) {
                transferSpeedLimit = calculateDynamicBandwidthLimit();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void enableAutoBandwidthLimit() {
        autoBandwidthLimit = true;
        startBandwidthMonitor();
    }

    public void disableAutoBandwidthLimit() {
        autoBandwidthLimit = false;
        transferSpeedLimit = 0;
    }

    public Map<String, Double> getTransferProgress() {
        return transferProgress;
    }

    public void setAutoAcceptFiles(boolean autoAccept) {
        autoAcceptFiles = autoAccept;
    }

    public boolean isAutoAcceptFiles() {
        return autoAcceptFiles;
    }

    public void setTransferSpeedLimit(long limit) {
        transferSpeedLimit = limit;
    }

    public String getSavePath() {
        return savePath;
    }

    public void setSavePath(String path) {
        savePath = path;
    }

    public void startAutoBackup() {
        new Thread(() -> {
            while (true) {
                try {
                    String backupPath = savePath + "/backup_" + System.currentTimeMillis() + ".csv";
                    databaseManager.exportBackup(backupPath);
                    databaseManager.logActivity(deviceManager.getUserUUID(), "Backup created: " + backupPath);
                    Platform.runLater(() -> notify(getResourceString("auto_backup_completed") + backupPath));
                    Thread.sleep(3600000);
                } catch (Exception e) {
                    Platform.runLater(() -> notify("Backup error: " + e.getMessage()));
                }
            }
        }).start();
    }

    public Map<String, Integer> getPendingNotifications() {
        return pendingNotifications;
    }

    public void clearNotification(String fileName) {
        pendingNotifications.remove(fileName);
    }

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void notify(String message) {
        Platform.runLater(() -> System.out.println(message));
    }
}