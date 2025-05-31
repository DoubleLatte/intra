import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.util.*;

public class SettingsTab {
    private static final String CURRENT_VERSION = "1.0.0";
    private static final int PORT = 12345;
    private static String userUUID = UUID.randomUUID().toString();
    private static String userName = "User_" + userUUID.substring(0, 8);
    private static String avatarPath = "";
    private TextArea updateNotesArea;
    private TextField userNameField;
    private TextField speedLimitField;

    public Tab createTab() {
        Tab tab = new Tab(getResourceString("settings_tab"));
        tab.setClosable(false);

        updateNotesArea = new TextArea();
        updateNotesArea.setEditable(false);
        Button checkUpdateButton = new Button(getResourceString("check_update"));
        userNameField = new TextField(userName);
        Button updateProfileButton = new Button(getResourceString("update_profile"));
        Button chooseAvatarButton = new Button(getResourceString("choose_avatar"));
        speedLimitField = new TextField("0");
        speedLimitField.setPromptText(getResourceString("speed_limit_prompt"));
        Button applySpeedLimitButton = new Button(getResourceString("apply_speed_limit"));
        ComboBox<String> languageComboBox = new ComboBox<>();
        languageComboBox.getItems().addAll("ko", "en");
        languageComboBox.setValue(Locale.getDefault().getLanguage());
        Button applyLanguageButton = new Button(getResourceString("apply_language"));
        ComboBox<String> themeComboBox = new ComboBox<>();
        themeComboBox.getItems().addAll("Light", "Dark");
        themeComboBox.setValue("Light");
        Button applyThemeButton = new Button(getResourceString("apply_theme"));
        Button exportLogButton = new Button(getResourceString("export_log"));

        VBox layout = new VBox(10,
                new Label(getResourceString("update")), checkUpdateButton, new Label(getResourceString("patch_notes")), updateNotesArea,
                new Label(getResourceString("user_name")), userNameField, chooseAvatarButton, updateProfileButton,
                new Label(getResourceString("speed_limit")), speedLimitField, applySpeedLimitButton,
                new Label(getResourceString("language")), languageComboBox, applyLanguageButton,
                new Label(getResourceString("theme")), themeComboBox, applyThemeButton,
                new Label(getResourceString("log_backup")), exportLogButton);
        tab.setContent(layout);

        checkUpdateButton.setOnAction(e -> checkForUpdates());
        updateProfileButton.setOnAction(e -> updateProfile());
        chooseAvatarButton.setOnAction(e -> chooseAvatar());
        applySpeedLimitButton.setOnAction(e -> applySpeedLimit());
        applyLanguageButton.setOnAction(e -> applyLanguage(languageComboBox.getValue()));
        applyThemeButton.setOnAction(e -> applyTheme(themeComboBox.getValue()));
        exportLogButton.setOnAction(e -> exportLog());

        return tab;
    }

    private String getResourceString(String key) {
        return ResourceBundle.getBundle("messages", Locale.getDefault()).getString(key);
    }

    private void checkForUpdates() {
        MainWindow mainWindow = new MainWindow();
        Map<String, String> discoveredDevices = mainWindow.getDiscoveredDevices();
        discoveredDevices.forEach((name, address) -> {
            try (Socket socket = new Socket(address, PORT);
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dis = new DataInputStream(socket.getInputStream())) {
                dos.writeUTF(userUUID);
                dos.writeUTF("VERSION");
                dos.writeUTF(CURRENT_VERSION);
                String remoteVersion = dis.readUTF();
                if (compareVersions(remoteVersion, CURRENT_VERSION) > 0) {
                    requestUpdate(address, remoteVersion);
                } else {
                    Platform.runLater(() -> updateNotesArea.setText(getResourceString("up_to_date") + CURRENT_VERSION));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void requestUpdate(String address, String newVersion) {
        try (Socket socket = new Socket(address, PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            dos.writeUTF(userUUID);
            dos.writeUTF("UPDATE");
            String patchNotes = dis.readUTF();
            long fileSize = dis.readLong();
            FileOutputStream fos = new FileOutputStream("update_" + newVersion + ".jar");
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
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("update_completed") + newVersion + "\n" + getResourceString("patch_notes") + ":\n" + notes));
        } catch (IOException e) {
            e.printStackTrace();
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

    private void updateProfile() {
        userName = userNameField.getText().trim();
        if (userName.isEmpty()) {
            userName = "User_" + userUUID.substring(0, 8);
        }
        Platform.runLater(() -> updateNotesArea.setText(getResourceString("profile_updated") + userName));
    }

    private void chooseAvatar() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            avatarPath = file.getAbsolutePath();
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("avatar_updated") + avatarPath));
        }
    }

    private void applySpeedLimit() {
        try {
            long limit = Long.parseLong(speedLimitField.getText().trim());
            MainWindow.transferSpeedLimit = limit >= 0 ? limit : 0;
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("speed_limit_applied") + MainWindow.transferSpeedLimit));
        } catch (NumberFormatException e) {
            Platform.runLater(() -> updateNotesArea.setText(getResourceString("invalid_speed_limit")));
        }
    }

    private void applyLanguage(String lang) {
        Locale.setDefault(new Locale(lang));
        Platform.runLater(() -> updateNotesArea.setText(getResourceString("language_applied")));
        // UI 갱신을 위해 애플리케이션 재시작 필요
    }

    private void applyTheme(String theme) {
        String css = theme.equals("Dark") ? "/dark.css" : "/style.css";
        Platform.runLater(() -> {
            App.getInstance().getScene().getStylesheets().clear();
            App.getInstance().getScene().getStylesheets().add(getClass().getResource(css).toExternalForm());
            updateNotesArea.setText(getResourceString("theme_applied") + theme);
        });
    }

    private void exportLog() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            try (CSVPrinter printer = new CSVPrinter(new FileWriter(file), CSVFormat.DEFAULT)) {
                printer.printRecord("ID", "File Name", "Type", "Size", "Timestamp");
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:transfer_log.db")) {
                    ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM transfers");
                    while (rs.next()) {
                        printer.printRecord(rs.getInt("id"), rs.getString("file_name"),
                                rs.getString("type"), rs.getLong("size"), rs.getString("timestamp"));
                    }
                }
                Platform.runLater(() -> updateNotesArea.setText(getResourceString("log_exported") + file.getAbsolutePath()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}