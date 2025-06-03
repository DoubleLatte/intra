package filesharing.main;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.io.File;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class UIManager {
    private final DeviceManager deviceManager;
    private final ChatManager chatManager;
    private final FileTransferManager fileTransferManager;
    private final StatsManager statsManager;
    private final DatabaseManager databaseManager;
    private ListView<String> deviceListView;
    private TextArea chatArea;
    private TextField chatInput;
    private TextField searchBar;
    private ProgressBar progressBar;
    private ImageView avatarView;
    private ListView<String> userListView;
    private static String avatarPath = "";
    private Label statusLabel;
    private Label notificationBadge;
    private BorderPane layout;

    public UIManager(DeviceManager deviceManager, ChatManager chatManager, FileTransferManager fileTransferManager, StatsManager statsManager) {
        this.deviceManager = deviceManager;
        this.chatManager = chatManager;
        this.fileTransferManager = fileTransferManager;
        this.statsManager = statsManager;
        this.databaseManager = new DatabaseManager();
    }

    public Tab createMainTab() {
        Tab tab = new Tab(getResourceString("main_tab"));
        tab.setClosable(false);

        // Left Sidebar: Device List and User Profile
        deviceListView = new ListView<>();
        deviceListView.getStyleClass().add("sidebar-list");
        updateDeviceList();
        avatarView = new ImageView();
        avatarView.setFitWidth(40);
        avatarView.setFitHeight(40);
        updateAvatar();
        statusLabel = new Label(deviceManager.getUserStatus());
        statusLabel.getStyleClass().add("status-label");
        Button settingsButton = new Button();
        settingsButton.getStyleClass().add("icon-button");
        settingsButton.setTooltip(new Tooltip(getResourceString("settings_tab")));
        addButtonAnimation(settingsButton);
        settingsButton.setOnAction(e -> openSettings());
        notificationBadge = new Label("0");
        notificationBadge.getStyleClass().add("notification-badge");
        notificationBadge.setVisible(false);
        StackPane deviceListPane = new StackPane(deviceListView, notificationBadge);
        StackPane.setAlignment(notificationBadge, Pos.TOP_RIGHT);
        VBox sidebar = new VBox(15, new Label(getResourceString("device_list")), deviceListPane, 
                               new HBox(10, avatarView, statusLabel), settingsButton);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_CENTER);
        sidebar.setPadding(new Insets(20, 10, 10, 10));

        // Center Panel: Chat and Controls
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.getStyleClass().add("chat-area");
        chatInput = new TextField();
        chatInput.setPromptText(getResourceString("type_message"));
        chatInput.getStyleClass().add("ios-text-field");
        Button sendChatButton = new Button(getResourceString("send"));
        sendChatButton.getStyleClass().add("action-button");
        addButtonAnimation(sendChatButton);
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.getStyleClass().add("ios-progress-bar");
        HBox chatControls = new HBox(8, chatInput, sendChatButton);
        chatControls.setAlignment(Pos.CENTER);
        searchBar = new TextField();
        searchBar.setPromptText(getResourceString("search_chat_prompt"));
        searchBar.getStyleClass().add("ios-text-field");
        Button searchButton = new Button(getResourceString("search"));
        searchButton.getStyleClass().add("action-button");
        addButtonAnimation(searchButton);
        HBox searchBox = new HBox(8, searchBar, searchButton);
        searchBox.setPadding(new Insets(0, 0, 10, 0));
        VBox centerPanel = new VBox(15, searchBox, chatArea, chatControls, progressBar);
        centerPanel.getStyleClass().add("center-panel");
        centerPanel.setPadding(new Insets(20));

        // Right Panel: User List and Quick Actions
        userListView = new ListView<>();
        updateUserList();
        Button sendFileButton = new Button(getResourceString("send_file"));
        sendFileButton.getStyleClass().add("action-button");
        addButtonAnimation(sendFileButton);
        Button viewStatsButton = new Button(getResourceString("view_stats"));
        viewStatsButton.getStyleClass().add("action-button");
        addButtonAnimation(viewStatsButton);
        Button cancelTransferButton = new Button(getResourceString("cancel_transfer"));
        cancelTransferButton.getStyleClass().add("action-button");
        addButtonAnimation(cancelTransferButton);
        Button viewActivityLogButton = new Button(getResourceString("view_activity_log"));
        viewActivityLogButton.getStyleClass().add("action-button");
        addButtonAnimation(viewActivityLogButton);
        Button viewFileVersionsButton = new Button(getResourceString("view_file_versions"));
        viewFileVersionsButton.getStyleClass().add("action-button");
        addButtonAnimation(viewFileVersionsButton);
        VBox rightPanel = new VBox(15, new Label(getResourceString("users")), userListView, 
                                  sendFileButton, viewStatsButton, cancelTransferButton, 
                                  viewActivityLogButton, viewFileVersionsButton);
        rightPanel.getStyleClass().add("right-panel");
        rightPanel.setPadding(new Insets(20, 10, 10, 10));

        // Main Layout
        layout = new BorderPane();
        layout.setLeft(sidebar);
        layout.setCenter(centerPanel);
        layout.setRight(rightPanel);
        tab.setContent(layout);

        // Event Handlers
        sendChatButton.setOnAction(e -> {
            chatManager.sendChat(deviceListView.getSelectionModel().getSelectedItem(), chatInput.getText(), chatArea, chatInput);
            chatManager.clearNotification(deviceListView.getSelectionModel().getSelectedItem());
            updateNotificationBadge();
        });
        sendFileButton.setOnAction(e -> fileTransferManager.sendFileOrFolder(deviceListView.getSelectionModel().getSelectedItem(), new TextInputDialog(), progressBar));
        searchButton.setOnAction(e -> searchChatLog());
        cancelTransferButton.setOnAction(e -> fileTransferManager.cancelTransfer(deviceListView.getSelectionModel().getSelectedItem()));
        viewStatsButton.setOnAction(e -> statsManager.showDashboard());
        viewActivityLogButton.setOnAction(e -> showActivityLog());
        viewFileVersionsButton.setOnAction(e -> showFileVersions());
        deviceListView.getSelectionModel().selectedItemProperty().addListener((obs, old, newValue) -> {
            if (newValue != null) {
                chatManager.clearNotification(newValue);
                fileTransferManager.clearNotification(newValue);
                updateNotificationBadge();
            }
        });

        // Drag and Drop for File Transfer
        chatArea.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        chatArea.setOnDragDropped(event -> {
            var db = event.getDragboard();
            if (db.hasFiles()) {
                String target = deviceListView.getSelectionModel().getSelectedItem();
                if (target != null) {
                    TextInputDialog tagDialog = new TextInputDialog();
                    tagDialog.setHeaderText(getResourceString("enter_tags"));
                    db.getFiles().forEach(file -> fileTransferManager.sendFileOrFolder(target, tagDialog, progressBar));
                } else {
                    notify(getResourceString("select_device"));
                }
            }
            event.setDropCompleted(true);
            event.consume();
        });

        // Real-time Updates
        startDeviceStatusUpdater();
        startUpdateListener(); // Start listening for update notifications

        return tab;
    }

    private void addButtonAnimation(Button button) {
        ScaleTransition scale = new ScaleTransition(Duration.millis(100), button);
        scale.setToX(1.1);
        scale.setToY(1.1);
        FadeTransition fade = new FadeTransition(Duration.millis(100), button);
        fade.setToValue(0.8);
        button.setOnMouseEntered(e -> {
            scale.playFromStart();
            fade.playFromStart();
        });
        button.setOnMouseExited(e -> {
            scale.setToX(1.0);
            scale.setToY(1.0);
            fade.setToValue(1.0);
            scale.playFromStart();
            fade.playFromStart();
        });
    }

    private void updateDeviceList() {
        Platform.runLater(() -> {
            deviceListView.getItems().clear();
            deviceManager.getDiscoveredDevices().forEach((name, address) -> {
                String status = deviceManager.getUserStatuses().getOrDefault(name, "Online");
                Integer notifications = fileTransferManager.getPendingNotifications().getOrDefault(name, 0) +
                                       chatManager.getPendingNotifications().getOrDefault(name, 0);
                String display = name + " (" + status + ")" + (notifications > 0 ? " [" + notifications + "]" : "");
                deviceListView.getItems().add(display);
            });
            updateNotificationBadge();
        });
    }

    private void updateUserList() {
        Platform.runLater(() -> {
            userListView.getItems().clear();
            deviceManager.getDiscoveredDevices().forEach((name, address) -> {
                String status = deviceManager.getUserStatuses().getOrDefault(name, "Online");
                userListView.getItems().add(name + " - " + status);
            });
        });
    }

    private void updateNotificationBadge() {
        Platform.runLater(() -> {
            int totalNotifications = fileTransferManager.getPendingNotifications().values().stream().mapToInt(Integer::intValue).sum() +
                                    chatManager.getPendingNotifications().values().stream().mapToInt(Integer::intValue).sum();
            notificationBadge.setText(String.valueOf(totalNotifications));
            notificationBadge.setVisible(totalNotifications > 0);
        });
    }

    private void startDeviceStatusUpdater() {
        new Thread(() -> {
            while (true) {
                updateDeviceList();
                updateUserList();
                statusLabel.setText(deviceManager.getUserStatus());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void startUpdateListener() {
        // Simulated update listener for P2P notifications
        new Thread(() -> {
            while (true) {
                // Simulate receiving update notification
                String version = "1.2.3";
                boolean isMainDeveloper = false; // Simulated third-party update
                showLatestUpdateVersion(version, isMainDeveloper);
                try {
                    Thread.sleep(3600000); // Check hourly
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void showLatestUpdateVersion(String version, boolean isMainDeveloper) {
        Platform.runLater(() -> {
            Label notification = new Label("New update available: v" + version);
            notification.getStyleClass().add("update-notification");
            if (!isMainDeveloper) {
                notification.setText(notification.getText() + " (Third-party)");
            }
            StackPane.setAlignment(notification, Pos.TOP_CENTER);
            layout.getChildren().add(notification);
            FadeTransition fade = new FadeTransition(Duration.seconds(5), notification);
            fade.setFromValue(1.0);
            fade.setToValue(0.0);
            fade.setOnFinished(e -> layout.getChildren().remove(notification));
            fade.play();
        });
    }

    private void searchChatLog() {
        String keyword = searchBar.getText();
        if (!keyword.isEmpty()) {
            ListView<String> logView = new ListView<>();
            chatManager.searchChatLog(keyword, logView);
            Stage searchStage = new Stage();
            searchStage.setTitle(getResourceString("chat_search_results"));
            searchStage.setScene(new Scene(new VBox(new Label(getResourceString("chat_search_results")), logView), 400, 300));
            searchStage.show();
        }
    }

    private void showActivityLog() {
        ListView<String> logView = new ListView<>();
        try (var conn = databaseManager.getActivityConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM activities");
            while (rs.next()) {
                logView.getItems().add(String.format("%s: %s at %s",
                        rs.getString("uuid"), rs.getString("action"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            notify("Activity log error: " + e.getMessage());
        }
        Stage logStage = new Stage();
        logStage.setTitle(getResourceString("activity_log"));
        logStage.setScene(new Scene(new VBox(new Label(getResourceString("activity_log")), logView), 400, 300));
        logStage.show();
    }

    private void showFileVersions() {
        ListView<String> versionView = new ListView<>();
        try (var conn = databaseManager.getVersionConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM versions");
            while (rs.next()) {
                versionView.getItems().add(String.format("%s (Version: %s, Size: %d, Hash: %s) at %s",
                        rs.getString("file_name"), rs.getString("version_name"), rs.getLong("size"),
                        rs.getString("hash"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            notify("File version error: " + e.getMessage());
        }
        versionView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selected = versionView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    String versionName = selected.split("Version: ")[1].split(",")[0];
                    restoreFileVersion(versionName);
                }
            }
        });
        Stage versionStage = new Stage();
        versionStage.setTitle(getResourceString("view_file_versions"));
        versionStage.setScene(new Scene(new VBox(new Label(getResourceString("file_versions")), versionView), 600, 400));
        versionStage.show();
    }

    private void restoreFileVersion(String versionName) {
        File versionFile = new File(fileTransferManager.getSavePath(), versionName);
        if (versionFile.exists()) {
            String originalName = versionName.split("\\.v")[0];
            File originalFile = new File(fileTransferManager.getSavePath(), originalName);
            try {
                Files.copy(versionFile.toPath(), originalFile.getAbsolutePath(), StandardCopyOption.REPLACE_EXISTING);
                databaseManager.logUpdateActivity("Restored file version: " + versionName + " to " + originalFile);
                notify("File version restored: " + originalFile);
            } catch (IOException e) {
                notify("Error downloading file version: " + e.getMessage());
            }
        } else {
            notify("File version not found");
        }
    }

    private void openSettings() {
        Stage settingsStage = new Stage();
        settingsStage.setTitle(getResourceString("settings_tab"));
        TabPane settingsTabs = new TabPane();
        settingsTabs.getTabs().add(new SettingsTab().createTab());
        settingsStage.setScene(new Scene(settingsTabs, 600, 400));
        settingsStage.show();
    }

    public void notify(String message) {
        if (SettingsTab.isNotificationsEnabled()) {
            Platform.runLater(() -> chatArea.appendText(getResourceString("notification") + message + "\n"));
        }
    }

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void updateAvatar() {
        if (!avatarPath.isEmpty()) {
            try {
                Image image = new Image(new File(avatarPath).toURI().toString());
                avatarView.setImage(image);
            } catch (Exception e) {
                notify("Avatar update error: " + e.getMessage());
            }
        }
    }

    public void setAvatarPath(String path) {
        avatarPath = path;
        updateAvatar();
    }
}