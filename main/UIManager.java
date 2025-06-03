package filesharing.main;

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
import java.util.*;

public class UIManager {
    private final DeviceManager deviceManager;
    private final ChatManager chatManager;
    private final FileTransferManager fileTransferManager;
    private final StatsManager statsManager;
    private ListView<String> deviceListView;
    private TextArea chatArea;
    private TextField chatInput;
    private TextField searchBar;
    private ProgressBar progressBar;
    private ImageView avatarView;
    private ListView<String> userListView;
    private static String avatarPath = "";
    private Label statusLabel;

    public UIManager(DeviceManager deviceManager, ChatManager chatManager, FileTransferManager fileTransferManager, StatsManager statsManager) {
        this.deviceManager = deviceManager;
        this.chatManager = chatManager;
        this.fileTransferManager = fileTransferManager;
        this.statsManager = statsManager;
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
        settingsButton.setOnAction(e -> openSettings());
        VBox sidebar = new VBox(10, new Label(getResourceString("device_list")), deviceListView, 
                               new HBox(10, avatarView, statusLabel), settingsButton);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_CENTER);
        sidebar.setPadding(new Insets(10));

        // Center Panel: Chat and Controls
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.getStyleClass().add("chat-area");
        chatInput = new TextField();
        chatInput.setPromptText(getResourceString("type_message"));
        Button sendChatButton = new Button(getResourceString("send"));
        sendChatButton.getStyleClass().add("action-button");
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        HBox chatControls = new HBox(5, chatInput, sendChatButton);
        chatControls.setAlignment(Pos.CENTER);
        searchBar = new TextField();
        searchBar.setPromptText(getResourceString("search_chat_prompt"));
        Button searchButton = new Button(getResourceString("search"));
        searchButton.getStyleClass().add("action-button");
        HBox searchBox = new HBox(5, searchBar, searchButton);
        VBox centerPanel = new VBox(10, searchBox, chatArea, chatControls, progressBar);
        centerPanel.getStyleClass().add("center-panel");
        centerPanel.setPadding(new Insets(10));

        // Right Panel: User List and Quick Actions
        userListView = new ListView<>();
        updateUserList();
        Button sendFileButton = new Button(getResourceString("send_file"));
        sendFileButton.getStyleClass().add("action-button");
        Button viewStatsButton = new Button(getResourceString("view_stats"));
        viewStatsButton.getStyleClass().add("action-button");
        Button cancelTransferButton = new Button(getResourceString("cancel_transfer"));
        cancelTransferButton.getStyleClass().add("action-button");
        VBox rightPanel = new VBox(10, new Label(getResourceString("users")), userListView, 
                                  sendFileButton, viewStatsButton, cancelTransferButton);
        rightPanel.getStyleClass().add("right-panel");
        rightPanel.setPadding(new Insets(10));

        // Main Layout
        BorderPane layout = new BorderPane();
        layout.setLeft(sidebar);
        layout.setCenter(centerPanel);
        layout.setRight(rightPanel);
        tab.setContent(layout);

        // Event Handlers
        sendChatButton.setOnAction(e -> chatManager.sendChat(deviceListView.getSelectionModel().getSelectedItem(), chatInput.getText(), chatArea, chatInput));
        sendFileButton.setOnAction(e -> fileTransferManager.sendFileOrFolder(deviceListView.getSelectionModel().getSelectedItem(), new TextInputDialog(), progressBar));
        searchButton.setOnAction(e -> searchChatLog());
        cancelTransferButton.setOnAction(e -> fileTransferManager.cancelTransfer(deviceListView.getSelectionModel().getSelectedItem()));
        viewStatsButton.setOnAction(e -> statsManager.showDashboard());

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

        return tab;
    }

    private void updateDeviceList() {
        Platform.runLater(() -> {
            deviceListView.getItems().clear();
            deviceManager.getDiscoveredDevices().forEach((name, address) -> {
                String status = deviceManager.getUserStatuses().getOrDefault(name, "Online");
                deviceListView.getItems().add(name + " (" + status + ")");
            });
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

    private void startDeviceStatusUpdater() {
        new Thread(() -> {
            while (true) {
                updateDeviceList();
                updateUserList();
                statusLabel.setText(deviceManager.getUserStatus());
                try {
                    Thread.sleep(5000); // 5초마다 업데이트
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
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