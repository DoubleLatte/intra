package filesharing.main;

import javafx.application.Platform;
import javafx.scene.control.Tab;
import java.io.IOException;

public class MainWindow {
    private final UIManager uiManager;
    private final DeviceManager deviceManager;
    private final ChatManager chatManager;
    private final FileTransferManager fileTransferManager;
    private final DatabaseManager databaseManager;
    private final SystemTrayManager systemTrayManager;
    private final SecurityManager securityManager;
    private final StatsManager statsManager;

    public MainWindow() {
        securityManager = new SecurityManager();
        databaseManager = new DatabaseManager();
        deviceManager = new DeviceManager();
        chatManager = new ChatManager(deviceManager, databaseManager, securityManager);
        fileTransferManager = new FileTransferManager(deviceManager, databaseManager, securityManager);
        statsManager = new StatsManager(databaseManager);
        uiManager = new UIManager(deviceManager, chatManager, fileTransferManager, statsManager);
        systemTrayManager = new SystemTrayManager(fileTransferManager);
    }

    public Tab createTab() {
        Tab tab = uiManager.createMainTab();
        try {
            deviceManager.setupMDNS();
            new Thread(deviceManager::startServer).start();
            new Thread(chatManager::startMulticastListener).start();
            systemTrayManager.setupSystemTray();
            databaseManager.initDatabases();
            chatManager.setupMediaPlayer();
            fileTransferManager.startAutoBackup();
            databaseManager.startLogCleanup();
        } catch (IOException e) {
            Platform.runLater(() -> uiManager.notify("Setup failed: " + e.getMessage()));
        }
        return tab;
    }
}