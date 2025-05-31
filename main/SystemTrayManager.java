package filesharing.main;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javafx.application.Platform;

public class SystemTrayManager {
    private final FileTransferManager fileTransferManager;
    private TrayIcon trayIcon;

    public SystemTrayManager(FileTransferManager fileTransferManager) {
        this.fileTransferManager = fileTransferManager;
    }

    public void setupSystemTray() {
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
                    Platform.exit();
                    tray.remove(trayIcon);
                });

                tray.add(trayIcon);
                updateTrayMenu();
            } catch (Exception e) {
                Platform.runLater(() -> notify("System tray error: " + e.getMessage()));
            }
        }
    }

    public void updateTrayMenu() {
        if (trayIcon == null) return;
        PopupMenu popup = trayIcon.getPopupMenu();
        Menu transfersMenu = (Menu) popup.getItem(1);
        transfersMenu.removeAll();
        fileTransferManager.getTransferProgress().forEach((fileName, progress) -> {
            MenuItem item = new MenuItem(String.format("%s: %.0f%%", fileName, progress * 100));
            item.setEnabled(false);
            transfersMenu.add(item);
        });
    }

    public void notify(String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(getResourceString("system_tray_title"), message, TrayIcon.MessageType.INFO);
        }
    }

    private String getResourceString(String key) {
        return java.util.ResourceBundle.getBundle("messages", java.util.Locale.getDefault()).getString(key);
    }
}