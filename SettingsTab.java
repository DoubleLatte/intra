package filesharing.main;

import filesharing.settings.SupportedLanguage;
import filesharing.settings.SupportedTheme;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class SettingsTab {
    private static boolean notificationsEnabled = true;
    private static String notificationSoundPath = "notification.wav";
    private static final String MAIN_DEVELOPER_ID = "main_dev_uuid";
    private final DeviceManager deviceManager;
    private final FileTransferManager fileTransferManager;
    private final DatabaseManager databaseManager;
    private final SecurityManager securityManager;
    private TextArea updateNotesArea;
    private ProgressBar progressBar;

    public SettingsTab() {
        this.deviceManager = new DeviceManager();
        this.fileTransferManager = new FileTransferManager(deviceManager, new DatabaseManager(), new SecurityManager());
        this.databaseManager = new DatabaseManager();
        this.securityManager = new SecurityManager();
    }

    public Tab createTab() {
        Tab settingsTab = new Tab(getResourceString("settings_tab"));
        settingsTab.setClosable(false);
        VBox settingsBox = new VBox(15);
        settingsBox.setPadding(new Insets(20));

        // Profile Settings
        Label profileLabel = new Label(getResourceString("user_name"));
        TextField profileField = new TextField(deviceManager.getUserName());
        Button updateProfileButton = new Button(getResourceString("update_profile"));
        updateProfileButton.getStyleClass().add("action-button");
        updateProfileButton.setOnAction(e -> {
            deviceManager.updateUserName(profileField.getText());
            notify(getResourceString("profile_updated") + profileField.getText());
        });

        // Avatar Settings
        Button avatarButton = new Button(getResourceString("choose_avatar"));
        avatarButton.getStyleClass().add("action-button");
        avatarButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg"));
            File file = fileChooser.showOpenDialog(null);
            if (file != null) {
                new UIManager(deviceManager, new ChatManager(deviceManager, databaseManager, securityManager), fileTransferManager, new StatsManager()).setAvatarPath(file.getAbsolutePath());
                notify(getResourceString("avatar_updated"));
            }
        });

        // Speed Limit Settings
        Label speedLimitLabel = new Label(getResourceString("speed_limit"));
        TextField speedLimitField = new TextField(String.valueOf(fileTransferManager.getTransferSpeedLimit()));
        Button applySpeedLimitButton = new Button(getResourceString("apply_speed_limit"));
        applySpeedLimitButton.getStyleClass().add("action-button");
        applySpeedLimitButton.setOnAction(e -> {
            try {
                long limit = Long.parseLong(speedLimitField.getText());
                fileTransferManager.setTransferSpeedLimit(limit);
                notify(getResourceString("speed_limit_applied") + limit);
            } catch (NumberFormatException ex) {
                notify(getResourceString("invalid_speed_limit"));
            }
        });

        // Auto Bandwidth Limit
        CheckBox autoBandwidthCheckBox = new CheckBox(getResourceString("auto_bandwidth_limit"));
        autoBandwidthCheckBox.setSelected(fileTransferManager.isAutoAcceptFiles());
        autoBandwidthCheckBox.setOnAction(e -> {
            if (autoBandwidthCheckBox.isSelected()) {
                fileTransferManager.enableAutoBandwidthLimit();
                notify(getResourceString("auto_bandwidth_enabled"));
            } else {
                fileTransferManager.disableAutoBandwidthLimit();
                notify(getResourceString("auto_bandwidth_disabled"));
            }
        });

        // Language Settings
        Label languageLabel = new Label(getResourceString("language"));
        ComboBox<String> languageCombo = new ComboBox<>();
        for (SupportedLanguage lang : SupportedLanguage.values()) {
            languageCombo.getItems().add(lang.getDisplayValue());
        }
        languageCombo.setValue(SupportedLanguage.ENGLISH.getDisplayValue());
        Button applyLanguageButton = new Button(getResourceString("apply_language"));
        applyLanguageButton.getStyleClass().add("action-button");
        applyLanguageButton.setOnAction(e -> {
            notify(getResourceString("language_updated") + languageCombo.getValue());
        });

        // Theme Settings
        Label themeLabel = new Label(getResourceString("theme"));
        ComboBox<String> themeCombo = new ComboBox<>();
        for (SupportedTheme theme : SupportedTheme.values()) {
            themeCombo.getItems().add(theme.getDisplayName());
        }
        themeCombo.setValue(SupportedTheme.DARK.getDisplayName());
        Button applyThemeButton = new Button(getResourceString("apply_theme"));
        applyThemeButton.getStyleClass().add("action-button");
        applyThemeButton.setOnAction(e -> {
            String selectedTheme = themeCombo.getValue();
            String cssFile = selectedTheme.equals(SupportedTheme.DARK.getDisplayName()) ? "/dark.css" : "/style.css";
            settingsTab.getContent().getScene().getStylesheets().clear();
            settingsTab.getContent().getScene().getStylesheets().add(getClass().getResource(cssFile).toExternalForm());
            notify(getResourceString("theme_updated") + selectedTheme);
        });

        // Log Backup
        Button logBackupButton = new Button(getResourceString("log_backup"));
        logBackupButton.getStyleClass().add("action-button");
        logBackupButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            File file = fileChooser.showSaveDialog(null);
            if (file != null) {
                try {
                    fileTransferManager.startAutoBackup();
                    databaseManager.exportBackup(file.getAbsolutePath());
                    notify(getResourceString("log_exported"));
                } catch (Exception ex) {
                    notify("Log export error: " + ex.getMessage());
                }
            }
        });

        // Save Path Settings
        Label savePathLabel = new Label(getResourceString("save_path"));
        TextField savePathField = new TextField(fileTransferManager.getSavePath());
        Button chooseSavePathButton = new Button(getResourceString("choose_save_path"));
        chooseSavePathButton.getStyleClass().add("action-button");
        chooseSavePathButton.setOnAction(e -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            File dir = directoryChooser.showDialog(null);
            if (dir != null) {
                fileTransferManager.setSavePath(dir.getAbsolutePath());
                savePathField.setText(dir.getAbsolutePath());
                notify(getResourceString("save_path_updated"));
            }
        });

        // Notification Settings
        CheckBox notificationCheckBox = new CheckBox(getResourceString("enable_notifications"));
        notificationCheckBox.setSelected(notificationsEnabled);
        notificationCheckBox.setOnAction(e -> {
            notificationsEnabled = notificationCheckBox.isSelected();
            notify(notificationsEnabled ? getResourceString("notifications_enabled") : getResourceString("notifications_disabled"));
        });

        Button notificationSoundButton = new Button(getResourceString("choose_notification_sound"));
        notificationSoundButton.getStyleClass().add("action-button");
        notificationSoundButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Files", "*.wav", "*.mp3"));
            File file = fileChooser.showOpenDialog(null);
            if (file != null) {
                notificationSoundPath = file.getAbsolutePath();
                notify(getResourceString("notification_sound_updated"));
            }
        });

        // User Status Settings
        Label statusLabel = new Label(getResourceString("user_status"));
        TextField statusField = new TextField(deviceManager.getUserStatus());
        Button applyStatusButton = new Button(getResourceString("apply_status"));
        applyStatusButton.getStyleClass().add("action-button");
        applyStatusButton.setOnAction(e -> {
            deviceManager.setUserStatus(statusField.getText());
            notify(getResourceString("status_updated") + statusField.getText());
        });

        // Update Settings
        Label updateLabel = new Label(getResourceString("check_update"));
        updateNotesArea = new TextArea();
        updateNotesArea.setEditable(false);
        updateNotesArea.setPrefHeight(100);
        Button checkUpdateButton = new Button(getResourceString("check_update"));
        checkUpdateButton.getStyleClass().add("action-button");
        checkUpdateButton.setOnAction(e -> checkForUpdates());

        Button manualUpdateButton = new Button(getResourceString("manual_update"));
        manualUpdateButton.getStyleClass().add("action-button");
        manualUpdateButton.setOnAction(e -> applyManualUpdate());

        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.getStyleClass().add("ios-progress-bar");

        VBox updateBox = new VBox(10, updateLabel, updateNotesArea, checkUpdateButton, manualUpdateButton, progressBar);

        settingsBox.getChildren().addAll(
                new HBox(10, profileLabel, profileField, updateProfileButton),
                avatarButton,
                new HBox(10, speedLimitLabel, speedLimitField, applySpeedLimitButton),
                autoBandwidthCheckBox,
                new HBox(10, languageLabel, languageCombo, applyLanguageButton),
                new HBox(10, themeLabel, themeCombo, applyThemeButton),
                logBackupButton,
                new HBox(10, savePathLabel, savePathField, chooseSavePathButton),
                notificationCheckBox,
                notificationSoundButton,
                new HBox(10, statusLabel, statusField, applyStatusButton),
                updateBox
        );

        ScrollPane scrollPane = new ScrollPane(settingsBox);
        scrollPane.setFitToWidth(true);
        settingsTab.setContent(scrollPane);
        return settingsTab;
    }

    private void checkForUpdates() {
        updateNotesArea.clear();
        progressBar.setVisible(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                // P2P-based update check (simulated)
                String targetDevice = deviceManager.getDiscoveredDevices().keySet().iterator().next();
                String address = deviceManager.getDiscoveredDevices().get(targetDevice);
                try (var socket = securityManager.createSSLSocket(address, 12345)) {
                    socket.startHandshake();
                    try (var dos = new DataOutputStream(socket.getOutputStream());
                         var dis = new DataInputStream(socket.getInputStream())) {
                        dos.writeUTF("UPDATE_CHECK");
                        String response = dis.readUTF();
                        // Parse response (simulated JSON)
                        Map<String, String> metadata = parseUpdateResponse(response);
                        String version = metadata.getOrDefault("version", "unknown");
                        String developer = metadata.getOrDefault("developer_id", "unknown");
                        String patchNotes = metadata.getOrDefault("patch_notes", "");
                        String signature = metadata.getOrDefault("signature", "");

                        PlatformManager.runLater(() -> {
                            updateNotesArea.setText(String.format("%s\n%s: %s\n%s", 
                                    getResourceString("patch_notes"), 
                                    getResourceString("version"), version, patchNotes));
                            progressBar.setProgress(0.5);
                        });

                        // Receive update JAR
                        File tempFile = File.createTempFile("update", ".jar");
                        receiveUpdateFile(tempFile, dis);
                        boolean isMainDeveloper = developer.equals(MAIN_DEVELOPER_ID);
                        boolean isSigned = signature.isEmpty() ? false : 
                            securityManager.verifySignature(tempFile, signature, getPublicKey());

                        PlatformManager.runLater(() -> {
                            progressBar.setProgress(1.0);
                            progressBar.setVisible(false);
                            applyUpdate(tempFile, version, developer, isMainDeveloper, isSigned, patchNotes);
                        });
                    }
                }
            } catch (Exception e) {
                PlatformManager.runLater(() -> {
                    updateNotesArea.setText("Update check error: " + e.getMessage());
                    progressBar.setVisible(false);
                    databaseManager.logUpdateActivity("Update check failed: " + e.getMessage());
                });
            } finally {
                executor.shutdown();
            }
        });
    }

    private void applyManualUpdate() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            progressBar.setVisible(true);
            try {
                Map<String, String> metadata = parseUpdateMetadata(file);
                String version = metadata.getOrDefault("version", "unknown");
                String developer = metadata.getOrDefault("developer_id", "unknown");
                String signature = metadata.getOrDefault("signature", "");
                boolean isMainDeveloper = developer.equals(MAIN_DEVELOPER_ID);
                boolean isSigned = signature.isEmpty() ? false : 
                             securityManager.verifySignature(file, signature, getPublicKey());

                PlatformManager.runLater(() -> {
                    progressBar.setVisible(false);
                    applyUpdate(file, version, developer, isMainDeveloper, isSigned, "");
                });
            } catch (IOException e) {
                PlatformManager.runLater(() -> {
                    progressBar.setVisible(false);
                    notify("Metadata parsing error: " + e.getMessage());
                    databaseManager.logUpdateActivity("Manual update metadata parsing failed: " + e.getMessage());
                });
            }
        }
    }

    private void applyUpdate(File updateFile, String version, String developer, boolean isMainDeveloper, boolean isSigned, String patchNotes) {
        Runnable apply = () -> {
            try {
                Files.copy(updateFile.toPath(), Paths.get("backup.jar"), StandardCopyOption.REPLACE_EXISTING);
                installAndRestart(updateFile);
                databaseManager.logUpdateActivity(String.format("Applied %s update v%s", 
                        isMainDeveloper ? "main developer" : "third-party", version));
                notify(getResourceString(isMainDeveloper ? "update_completed" : "manual_update_applied"));
            } catch (IOException e) {
                rollbackUpdate();
                databaseManager.logUpdateActivity(String.format("%s update v%s failed: %s", 
                        isMainDeveloper ? "Main developer" : "Third-party", version, e.getMessage()));
                notify("Update error: " + e.getMessage());
            }
        };

        if (!isMainDeveloper || !isSigned) {
            showThirdPartyWarning(updateFile, apply);
        } else {
            apply.run();
        }
    }

    private void showThirdPartyWarning(File updateFile, Runnable onConfirm) {
        PlatformManager.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(getResourceString("third_party_warning"));
            alert.setHeaderText("This update is from a third-party developer.");
            alert.setContentText("Applying this update may pose risks. Proceed with caution.");
            alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            alert.showAndWait().ifPresent(type -> {
                if (type == ButtonType.OK) {
                    onConfirm.run();
                }
            });
        });
    }

    private void rollbackUpdate() {
        try {
            Files.copy(Paths.get("backup.jar"), Paths.get("system.jar"), StandardCopyOption.REPLACE_EXISTING);
            databaseManager.logUpdateActivity("Update rolled back");
            PlatformManager.runLater(() -> updateNotesArea.setText(getResourceString("update_rolled_back")));
        } catch (IOException e) {
            databaseManager.logUpdateActivity("Rollback failed: " + e.getMessage());
            PlatformManager.runLater(() -> updateNotesArea.setText("Rollback error: " + e.getMessage()));
        }
    }

    private void installAndRestart(File file) throws IOException {
        String jarPath = file.getAbsolutePath();
        Files.copy(file.toPath(), Paths.get("system_new.jar"), StandardCopyOption.REPLACE_EXISTING);
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", "system_new.jar");
        pb.start();
        databaseManager.logUpdateActivity("Application restarted for update");
        PlatformManager.exit();
    }

    private Map<String, String> parseUpdateMetadata(File jarFile) throws IOException {
        // Simplified; use Jackson or similar in production
        return Map.of("version", "unknown", "developer_id", "unknown", "signature", "");
    }

    private Map<String, String> parseUpdateResponse(String response) {
        // Simplified; parse JSON in production
        return Map.of("version", "1.2.3", "developer_id", "third_party_dev_uuid", 
                      "patch_notes", "Custom enhancements", "signature", "dummy_signature");
    }

    private void receiveUpdateFile(File tempFile, DataInputStream dis) throws IOException {
        // Simplified; implement actual file transfer
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = dis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        }
    }

    private PublicKey getPublicKey() {
        // Placeholder; load actual public key
        return null;
    }

    public static boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public static String getNotificationSoundPath() {
        return notificationSoundPath;
    }

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void notify(String message) {
        PlatformManager.runLater(() -> System.out.println(message));
    }
}