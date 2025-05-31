package filesharing.settings;

import filesharing.main.App;
import filesharing.main.DeviceManager;
import filesharing.main.FileTransferManager;
import filesharing.main.SecurityManager;
import filesharing.main.UIManager;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.util.*;

public class SettingsTab {
    private static final String CURRENT_VERSION = "1.0.0";
    private static final int PORT = 12345;
    private static final String DEVELOPER_NAME = "MainDeveloper";
    private TextArea updateNotesArea;
    private final DeviceManager deviceManager;
    private final FileTransferManager fileTransferManager;
    private final SecurityManager securityManager;
    private final UIManager uiManager;
    private static String notificationSoundPath = "notification.wav";
    private static boolean notificationsEnabled = true;

    public SettingsTab() {
        this.deviceManager = new DeviceManager();
        this.securityManager = new SecurityManager();
        this.fileTransferManager = new FileTransferManager(deviceManager, null, securityManager);
        this.uiManager = new UIManager(deviceManager, null, fileTransferManager, null);
    }

    public Tab createTab() {
        Tab tab = new Tab(getResourceString("settings_tab"));
        tab.setClosable(false);

        updateNotesArea = new TextArea();
        updateNotesArea.setEditable(false);
        Button checkUpdateButton = new Button(getResourceString("check_update"));
        Button manualUpdateButton = new Button(getResourceString("manual_update"));
        TextField userNameField = new TextField(deviceManager.getUserName());
        Button updateProfileButton = new Button(getResourceString("update_profile"));
        Button chooseAvatarButton = new Button(getResourceString("choose_avatar"));
        TextField speedLimitField = new TextField("0");
        speedLimitField.setPromptText(getResourceString("speed_limit_prompt"));
        Button applySpeedLimitButton = new Button(getResourceString("apply_speed_limit"));
        CheckBox autoBandwidthLimitCheckBox = new CheckBox(getResourceString("auto_bandwidth_limit"));
        ComboBox<String> languageComboBox = new ComboBox<>();
        languageComboBox.getItems().addAll("ko", "en");
        languageComboBox.setValue(Locale.getDefault().getLanguage());
        Button applyLanguageButton = new Button(getResourceString("apply_language"));
        ComboBox<String> themeComboBox = new ComboBox<>();
        themeComboBox.getItems().addAll("Light", "Dark");
        themeComboBox.setValue("Light");
        Button applyThemeButton = new Button(getResourceString("apply_theme"));
        Button exportLogButton = new Button(getResourceString("export_log"));
        TextField savePathField = new TextField(fileTransferManager.getSavePath());
        Button chooseSavePathButton = new Button(getResourceString("choose_save_path"));
        CheckBox notificationCheckBox = new CheckBox(getResourceString("enable_notifications"));
        notificationCheckBox.setSelected(notificationsEnabled);
        Button chooseSoundButton = new Button(getResourceString("choose_notification_sound"));
        ComboBox<String> userStatusComboBox = new ComboBox<>();
        userStatusComboBox.getItems().addAll("Online", "Busy", "Away");
        userStatusComboBox.setValue("Online");
        Button applyStatusButton = new Button(getResourceString("apply_status"));

        VBox layout = new VBox(10,
                new Label(getResourceString("update")), checkUpdateButton, manualUpdateButton, new Label(getResourceString("patch_notes")), updateNotesArea,
                new Label(getResourceString("user_name")), userNameField, chooseAvatarButton, updateProfileButton,
                new Label(getResourceString("speed_limit")), speedLimitField, applySpeedLimitButton, autoBandwidthLimitCheckBox,
                new Label(getResourceString("language")), languageComboBox, applyLanguageButton,
                new Label(getResourceString("theme")), themeComboBox, applyThemeButton,
                new Label(getResourceString("log_backup")), exportLogButton,
                new Label(getResourceString("save_path")), savePathField, chooseSavePathButton,
                new Label(getResourceString("notifications")), notificationCheckBox, chooseSoundButton,
                new Label(getResourceString("user_status")), userStatusComboBox, applyStatusButton);
        tab.setContent(layout);

        checkUpdateButton.setOnAction(e -> checkForUpdates());
        manualUpdateButton.setOnAction(e -> applyManualUpdate());
        updateProfileButton.setOnAction(e -> updateProfile(userNameField.getText()));
        chooseAvatarButton.setOnAction(e -> chooseAvatar());
        applySpeedLimitButton.setOnAction(e -> applySpeedLimit(speedLimitField.getText()));
        autoBandwidthLimitCheckBox.setOnAction(e -> toggleAutoBandwidthLimit(autoBandwidthLimitCheckBox.isSelected()));
        applyLanguageButton.setOnAction(e -> applyLanguage(languageComboBox.getValue()));
        applyThemeButton.setOnAction(e -> applyTheme(themeComboBox.getValue()));
        exportLogButton.setOnAction(e -> exportLog());
        chooseSavePathButton.setOnAction(e -> chooseSavePath(savePathField));
        notificationCheckBox.setOnAction(e -> toggleNotifications(notificationCheckBox.isSelected()));
        chooseSoundButton.setOnAction(e -> chooseNotificationSound());
        applyStatusButton.setOnAction(e -> applyUserStatus(userStatusComboBox.getValue()));

        return tab;
    }

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void checkForUpdates() {
        deviceManager.getDiscoveredDevices().forEach((name, address) -> {
            try (var socket = securityManager.createSSLSocket(address, PORT)) {
                socket.startHandshake();
                try (var dos = new DataOutputStream(socket.getOutputStream());
                     var dis = new DataInputStream(socket.getInputStream())) {
                    dos.writeUTF(deviceManager.getUserUUID());
                    dos.writeUTF("VERSION");
                    dos.writeUTF(CURRENT_VERSION);
                    dos.writeUTF(DEVELOPER_NAME);
                    String remoteVersion = dis.readUTF();
                    String remoteDev = dis.readUTF();
                    if (!remoteDev.equals(DEVELOPER_NAME)) {
                        Platform.runLater(() -> updateNotesArea.appendText(getResourceString("third_party_warning") + "\n"));
                    }
                    if (compareVersions(remoteVersion, CURRENT_VERSION) > 0) {
                        requestUpdate(address, remoteVersion, remoteDev);
                    } else {
                        Platform.runLater(() -> updateNotesArea.setText(getResourceString("up_to_date") + CURRENT_VERSION));
                    }
                }
            } catch (IOException e) {
                Platform.runLater(() -> updateNotesArea.setText("Update check error: " + e.getMessage()));
            }
        });
    }

    private void requestUpdate(String address, String newVersion, String developer) {
        try (var socket = securityManager.createSSLSocket(address, PORT)) {
            socket.startHandshake();
            try (var dos = new DataOutputStream(socket.getOutputStream());
                 var dis = new DataInputStream(socket.getInputStream())) {
                dos.writeUTF(deviceManager.getUserUUID());
                dos.writeUTF("UPDATE");
                String patchNotes = dis.readUTF();
                long fileSize = dis.readLong();
                FileOutputStream fos = new FileOutputStream("update_" + newVersion + "_" + developer + ".jar");
                byte[] buffer = new byte[4096];
                long bytesRead = 0;
                int count;
                while (bytesRead < fileSize && (count = dis.read(buffer)) > 0) {
                    fos.write(buffer, 0, count);
                    bytesRead += count;
                }
                fos.close();
                Yaml yaml = new Yaml();
                Map<String, Object> notes = yaml.load(patchNotes);
                Platform.runLater(() -> updateNotesArea.setText(getResourceString("update_completed") + newVersion + " (" + developer + ")\n" + getResourceString("patch_notes") + ":\n" + notes));
            }
        } catch (IOException e) {
            Platform.runLater(() -> updateNotesArea.setText("Update error: " + e.getMessage()));
        }
    }

    private void applyManualUpdate() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            try {
                Runtime.getRuntime().exec("java -jar " + file.getAbsolutePath());
                Platform.runLater(() -> updateNotesArea.setText(getResourceString("manual_update_applied") + file.getAbsolutePath()));
                Platform.exit();
            } catch (IOException e) {
                Platform.runLater(() -> updateNotesArea.setText("Manual update error: " + e.getMessage()));
            }
        }
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
            int p1 = Integer.parseInt(parts1[i]);
            int p2 = Integer.parseInt(parts2[i]);
            if (p1 != p2) return p1 - p2;
        }
        return parts1.length - parts2.length;
    }

    private void updateProfile(String newName) {
        deviceManager.updateUserName(newName.trim().isEmpty() ? "User_" + deviceManager.getUserUUID().substring(0, 8) : newName);
        Platform.runLater(() -> updateNotesArea.setText(getResourceString("profile_updated") + deviceManager.getUserName()));
    }

    private void chooseAvatar() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            uiManager.setAvatarPath(file.getAbsolutePath());
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("avatar_updated") + file.getAbsolutePath()));
        }
    }

    private void applySpeedLimit(String limitText) {
        try {
            long limit = Long.parseLong(limitText);
            fileTransferManager.setTransferSpeedLimit(limit);
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("speed_limit_applied") + limit));
        } catch (NumberFormatException e) {
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("invalid_speed_limit")));
        }
    }

    private void toggleAutoBandwidthLimit(boolean enabled) {
        if (enabled) {
            fileTransferManager.enableAutoBandwidthLimit();
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("auto_bandwidth_enabled")));
        } else {
            fileTransferManager.disableAutoBandwidthLimit();
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("auto_bandwidth_disabled")));
        }
    }

    private void applyLanguage(String language) {
        Locale.setDefault(new Locale(language));
        App.getInstance().getScene().getStylesheets().clear();
        App.getInstance().getScene().getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        Platform.runLater(() -> updateNotesArea.setText(getResourceString("language_updated") + language));
    }

    private void applyTheme(String theme) {
        App.getInstance().getScene().getStylesheets().clear();
        App.getInstance().getScene().getStylesheets().add(getClass().getResource(theme.equals("Dark") ? "/dark.css" : "/style.css").toExternalForm());
        Platform.runLater(() -> updateNotesArea.setText(getResourceString("theme_updated") + theme));
    }

    private void exportLog() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            try {
                fileTransferManager.startAutoBackup(); // 백업 호출
                Platform.runLater(() -> updateNotesArea.setText(getResourceString("log_exported") + file.getAbsolutePath()));
            } catch (Exception e) {
                Platform.runLater(() -> updateNotesArea.setText("Log export error: " + e.getMessage()));
            }
        }
    }

    private void chooseSavePath(TextField savePathField) {
        DirectoryChooser dirChooser = new DirectoryChooser();
        File dir = dirChooser.showDialog(null);
        if (dir != null) {
            fileTransferManager.setSavePath(dir.getAbsolutePath());
            savePathField.setText(dir.getAbsolutePath());
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("save_path_updated") + dir.getAbsolutePath()));
        }
    }

    private void toggleNotifications(boolean enabled) {
        notificationsEnabled = enabled;
        Platform.runLater(() -> updateNotesArea.setText(getResourceString(enabled ? "notifications_enabled" : "notifications_disabled")));
    }

    private void chooseNotificationSound() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV Files", "*.wav"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            notificationSoundPath = file.getAbsolutePath();
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("notification_sound_updated") + file.getAbsolutePath()));
        }
    }

    private void applyUserStatus(String status) {
        deviceManager.setUserStatus(status);
        Platform.runLater(() -> updateNotesArea.setText(getResourceString("status_updated") + status));
    }

    public static String getNotificationSoundPath() {
        return notificationSoundPath;
    }

    public static boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }
}