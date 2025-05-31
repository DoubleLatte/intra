package filesharing.main;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class UIManager {
    private final DeviceManager deviceManager;
    private final ChatManager chatManager;
    private final FileTransferManager fileTransferManager;
    private final StatsManager statsManager;
    private ListView<String> deviceListView;
    private TextArea chatArea;
    private TextField chatInput;
    private ProgressBar progressBar;
    private TextField manualIpInput;
    private ImageView avatarView;
    private static String avatarPath = "";

    public UIManager(DeviceManager deviceManager, ChatManager chatManager, FileTransferManager fileTransferManager, StatsManager statsManager) {
        this.deviceManager = deviceManager;
        this.chatManager = chatManager;
        this.fileTransferManager = fileTransferManager;
        this.statsManager = statsManager;
    }

    public Tab createMainTab() {
        Tab tab = new Tab(getResourceString("main_tab"));
        tab.setClosable(false);

        deviceListView = new ListView<>();
        deviceListView.getItems().addAll(deviceManager.getDiscoveredDevices().keySet());
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatInput = new TextField();
        Button sendChatButton = new Button(getResourceString("send_chat"));
        Button sendFileButton = new Button(getResourceString("send_file_folder"));
        CheckBox autoAcceptCheckBox = new CheckBox(getResourceString("auto_accept_files"));
        Button viewLogButton = new Button(getResourceString("view_transfer_log"));
        Button viewChatLogButton = new Button(getResourceString("view_chat_log"));
        Button blockUserButton = new Button(getResourceString("block_user"));
        Button manageGroupsButton = new Button(getResourceString("manage_groups"));
        Button viewTagsButton = new Button(getResourceString("view_tags"));
        Button viewStatsButton = new Button(getResourceString("view_stats"));
        Button networkDiagnosticsButton = new Button(getResourceString("network_diagnostics"));
        Button previewFileButton = new Button(getResourceString("preview_file"));
        Button manageContactsButton = new Button(getResourceString("manage_contacts"));
        Button cancelTransferButton = new Button(getResourceString("cancel_transfer"));
        Button viewDownloadsButton = new Button(getResourceString("view_downloads"));
        Button searchChatButton = new Button(getResourceString("search_chat"));
        manualIpInput = new TextField();
        manualIpInput.setPromptText(getResourceString("manual_ip_prompt"));
        Button addManualIpButton = new Button(getResourceString("add_manual_ip"));
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        avatarView = new ImageView();
        avatarView.setFitWidth(50);
        avatarView.setFitHeight(50);
        updateAvatar();

        VBox controls = new VBox(10, new Label(getResourceString("device_list")), deviceListView,
                new Label(getResourceString("chat")), chatArea, chatInput, sendChatButton, searchChatButton,
                sendFileButton, autoAcceptCheckBox, viewLogButton, viewChatLogButton,
                blockUserButton, manageGroupsButton, viewTagsButton, viewStatsButton,
                networkDiagnosticsButton, previewFileButton, manageContactsButton, cancelTransferButton,
                viewDownloadsButton, new Label(getResourceString("manual_ip")), manualIpInput, addManualIpButton,
                progressBar, new Label(getResourceString("avatar")), avatarView);
        tab.setContent(controls);

        sendChatButton.setOnAction(e -> chatManager.sendChat(deviceListView.getSelectionModel().getSelectedItem(), chatInput.getText(), chatArea, chatInput));
        sendFileButton.setOnAction(e -> fileTransferManager.sendFileOrFolder(deviceListView.getSelectionModel().getSelectedItem(), new TextInputDialog(), progressBar));
        autoAcceptCheckBox.setOnAction(e -> fileTransferManager.setAutoAcceptFiles(autoAcceptCheckBox.isSelected()));
        viewLogButton.setOnAction(e -> showTransferLog());
        viewChatLogButton.setOnAction(e -> showChatLog());
        blockUserButton.setOnAction(e -> blockUser());
        manageGroupsButton.setOnAction(e -> manageGroups());
        viewTagsButton.setOnAction(e -> viewTags());
        viewStatsButton.setOnAction(e -> statsManager.showStats());
        networkDiagnosticsButton.setOnAction(e -> statsManager.runNetworkDiagnostics());
        previewFileButton.setOnAction(e -> previewFile());
        manageContactsButton.setOnAction(e -> manageContacts());
        cancelTransferButton.setOnAction(e -> fileTransferManager.cancelTransfer(deviceListView.getSelectionModel().getSelectedItem()));
        viewDownloadsButton.setOnAction(e -> showDownloadLog());
        searchChatButton.setOnAction(e -> searchChatLog());
        addManualIpButton.setOnAction(e -> deviceManager.addManualDevice(manualIpInput.getText(), () -> notify(getResourceString("manual_device_added") + manualIpInput.getText())));

        return tab;
    }

    private void showTransferLog() {
        ListView<String> logView = new ListView<>();
        try (var conn = databaseManager.getTransferConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM transfers");
            while (rs.next()) {
                logView.getItems().add(String.format("%s: %s (%d bytes) at %s\n%s",
                        rs.getString("type"), rs.getString("file_name"), rs.getLong("size"),
                        rs.getString("timestamp"), rs.getString("metadata")));
            }
        } catch (SQLException e) {
            notify("Transfer log error: " + e.getMessage());
        }
        Stage logStage = new Stage();
        logStage.setTitle(getResourceString("transfer_log"));
        logStage.setScene(new Scene(new VBox(new Label(getResourceString("transfer_log")), logView), 400, 300));
        logStage.show();
    }

    private void showChatLog() {
        ListView<String> logView = new ListView<>();
        try (var conn = databaseManager.getChatConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM chats");
            while (rs.next()) {
                logView.getItems().add(String.format("%s: %s (%s) at %s",
                        rs.getString("type"), rs.getString("message"), rs.getString("uuid"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            notify("Chat log error: " + e.getMessage());
        }
        Stage logStage = new Stage();
        logStage.setTitle(getResourceString("chat_log"));
        logStage.setScene(new Scene(new VBox(new Label(getResourceString("chat_log")), logView), 400, 300));
        logStage.show();
    }

    private void showDownloadLog() {
        ListView<String> logView = new ListView<>();
        try (var conn = databaseManager.getDownloadConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM downloads");
            while (rs.next()) {
                logView.getItems().add(String.format("%s at %s\n%s",
                        rs.getString("file_name"), rs.getString("timestamp"), rs.getString("metadata")));
            }
        } catch (SQLException e) {
            notify("Download log error: " + e.getMessage());
        }
        Stage logStage = new Stage();
        logStage.setTitle(getResourceString("download_log"));
        logStage.setScene(new Scene(new VBox(new Label(getResourceString("download_log")), logView), 400, 300));
        logStage.show();
    }

    private void searchChatLog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText(getResourceString("search_chat_prompt"));
        Optional<String> keyword = dialog.showAndWait();
        keyword.ifPresent(k -> {
            ListView<String> logView = new ListView<>();
            chatManager.searchChatLog(k, logView);
            Stage searchStage = new Stage();
            searchStage.setTitle(getResourceString("chat_search_results"));
            searchStage.setScene(new Scene(new VBox(new Label(getResourceString("chat_search_results")), logView), 400, 300));
            searchStage.show();
        });
    }

    private void blockUser() {
        String target = deviceListView.getSelectionModel().getSelectedItem();
        if (target == null) {
            notify(getResourceString("select_device"));
            return;
        }
        String uuid = target.split("_")[1];
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(getResourceString("block_user"));
        dialog.setHeaderText(getResourceString("configure_block") + target);
        CheckBox fileBlock = new CheckBox(getResourceString("block_files"));
        CheckBox messageBlock = new CheckBox(getResourceString("block_messages"));
        dialog.getDialogPane().setContent(new VBox(10, fileBlock, messageBlock));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(type -> {
            if (type == ButtonType.OK) {
                databaseManager.blockUser(uuid, fileBlock.isSelected(), messageBlock.isSelected());
                notify(getResourceString("user_blocked") + target);
            }
        });
    }

    private void manageGroups() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(getResourceString("manage_groups"));
        dialog.setHeaderText(getResourceString("create_group"));
        TextField groupNameField = new TextField();
        ListView<String> groupMembers = new ListView<>();
        groupMembers.getItems().addAll(deviceListView.getItems());
        groupMembers.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        dialog.getDialogPane().setContent(new VBox(10, new Label(getResourceString("group_name")), groupNameField,
                new Label(getResourceString("group_members")), groupMembers));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(type -> {
            if (type == ButtonType.OK) {
                String groupName = groupNameField.getText().trim();
                if (!groupName.isEmpty()) {
                    String members = String.join(",", groupMembers.getSelectionModel().getSelectedItems());
                    notify(getResourceString("group_created") + groupName + " (" + members + ")");
                    chatManager.sendGroupChat(getResourceString("group_message") + groupName);
                }
            }
        });
    }

    private void viewTags() {
        ListView<String> tagView = new ListView<>();
        try (var conn = databaseManager.getTransferConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM tags");
            while (rs.next()) {
                tagView.getItems().add(String.format("%s: %s at %s",
                        rs.getString("file_name"), rs.getString("tags"), rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            notify("Tag view error: " + e.getMessage());
        }
        Stage tagStage = new Stage();
        tagStage.setTitle(getResourceString("file_tags"));
        tagStage.setScene(new Scene(new VBox(new Label(getResourceString("file_tags")), tagView), 400, 300));
        tagStage.show();
    }

    private void previewFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg"),
                new FileChooser.ExtensionFilter("Text", "*.txt"));
        File file = fileChooser.showOpenDialog(null);
        if (file == null) return;

        Stage previewStage = new Stage();
        previewStage.setTitle(getResourceString("file_preview"));
        if (file.getName().endsWith(".png") || file.getName().endsWith(".jpg")) {
            Image image = new Image(file.toURI().toString());
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(400);
            imageView.setPreserveRatio(true);
            previewStage.setScene(new Scene(new VBox(new Label(file.getName()), imageView)));
        } else if (file.getName().endsWith(".txt")) {
            WebView webView = new WebView();
            try {
                String content = Files.readString(file.toPath());
                webView.getEngine().loadContent("<pre>" + content + "</pre>");
            } catch (IOException e) {
                notify("File preview error: " + e.getMessage());
            }
            previewStage.setScene(new Scene(new VBox(new Label(file.getName()), webView), 400, 300));
        }
        previewStage.show();
    }

    private void manageContacts() {
        String target = deviceListView.getSelectionModel().getSelectedItem();
        if (target == null) {
            notify(getResourceString("select_device"));
            return;
        }
        String uuid = target.split("_")[1];
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(getResourceString("manage_contacts"));
        dialog.setHeaderText(getResourceString("set_contact_grade") + target);
        ComboBox<String> gradeCombo = new ComboBox<>();
        gradeCombo.getItems().addAll("Green", "Orange", "Red");
        gradeCombo.setValue(databaseManager.getContactGrade(uuid));
        dialog.getDialogPane().setContent(new VBox(10, new Label(getResourceString("contact_grade")), gradeCombo));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(type -> {
            if (type == ButtonType.OK) {
                String grade = gradeCombo.getValue();
                databaseManager.setContactGrade(uuid, grade);
                notify(getResourceString("contact_updated") + target + " (" + grade + ")");
            }
        });
    }

    public void notify(String message) {
        Platform.runLater(() -> chatArea.appendText(getResourceString("notification") + message + "\n"));
    }

    private String getResourceString(String key) {
        return java.util.ResourceBundle.getBundle("messages", java.util.Locale.getDefault()).getString(key);
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