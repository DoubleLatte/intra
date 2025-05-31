package filesharing.main;

import filesharing.settings.SettingsTab;
import filesharing.sync.SyncTab;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

public class App extends Application {
    private static App instance;

    @Override
    public void start(Stage primaryStage) {
        instance = this;
        TabPane tabPane = new TabPane();
        tabPane.getTabs().addAll(
                new MainWindow().createTab(),
                new SettingsTab().createTab(),
                new SyncTab().createTab()
        );
        Scene scene = new Scene(tabPane, 600, 400);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        primaryStage.setTitle("파일 공유 시스템");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static App getInstance() {
        return instance;
    }

    public Scene getScene() {
        return getInstance().getPrimaryStage().getScene();
    }

    public static void main(String[] args) {
        launch(args);
    }
}