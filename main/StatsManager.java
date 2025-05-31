package filesharing.main;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import oshi.SystemInfo;
import oshi.hardware.NetworkIF;
import java.sql.*;

public class StatsManager {
    private final DatabaseManager databaseManager;
    private final SystemInfo systemInfo = new SystemInfo();

    public StatsManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void showStats() {
        PieChart chart = new PieChart();
        try (var conn = databaseManager.getTransferConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT type, COUNT(*) as count FROM transfers GROUP BY type");
            while (rs.next()) {
                chart.getData().add(new PieChart.Data(rs.getString("type"), rs.getInt("count")));
            }
        } catch (SQLException e) {
            notify("Stats error: " + e.getMessage());
        }
        try (var conn = databaseManager.getChatConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT type, COUNT(*) as count FROM chats GROUP BY type");
            while (rs.next()) {
                chart.getData().add(new PieChart.Data("Chat " + rs.getString("type"), rs.getInt("count")));
            }
        } catch (SQLException e) {
            notify("Chat stats error: " + e.getMessage());
        }
        Stage statsStage = new Stage();
        statsStage.setTitle(getResourceString("activity_stats"));
        statsStage.setScene(new Scene(new VBox(new Text(getResourceString("activity_stats")), chart), 400, 300));
        statsStage.show();
    }

    public void showDashboard() {
        PieChart transferChart = new PieChart();
        PieChart chatChart = new PieChart();
        try (var conn = databaseManager.getTransferConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT type, COUNT(*) as count FROM transfers GROUP BY type");
            while (rs.next()) {
                transferChart.getData().add(new PieChart.Data(rs.getString("type"), rs.getInt("count")));
            }
        } catch (SQLException e) {
            notify("Transfer dashboard error: " + e.getMessage());
        }
        try (var conn = databaseManager.getChatConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT type, COUNT(*) as count FROM chats GROUP BY type");
            while (rs.next()) {
                chatChart.getData().add(new PieChart.Data("Chat " + rs.getString("type"), rs.getInt("count")));
            }
        } catch (SQLException e) {
            notify("Chat dashboard error: " + e.getMessage());
        }
        Stage dashboardStage = new Stage();
        dashboardStage.setTitle(getResourceString("dashboard"));
        dashboardStage.setScene(new Scene(new VBox(
                new Text(getResourceString("transfer_stats")), transferChart,
                new Text(getResourceString("chat_stats")), chatChart
        ), 600, 600));
        dashboardStage.show();
    }

    public void runNetworkDiagnostics() {
        StringBuilder diagnostics = new StringBuilder();
        diagnostics.append(getResourceString("network_diagnostics")).append(":\n");
        NetworkIF[] interfaces = systemInfo.getHardware().getNetworkIFs();
        for (NetworkIF nif : interfaces) {
            diagnostics.append("Interface: ").append(nif.getName()).append("\n");
            diagnostics.append("Bytes Sent: ").append(nif.getBytesSent()).append("\n");
            diagnostics.append("Bytes Received: ").append(nif.getBytesRecv()).append("\n");
        }
        diagnostics.append("CPU Usage: ").append(systemInfo.getHardware().getProcessor().getSystemCpuLoad(1000) * 100).append("%\n");
        Platform.runLater(() -> {
            TextArea diagArea = new TextArea(diagnostics.toString());
            diagArea.setEditable(false);
            Stage diagStage = new Stage();
            diagStage.setTitle(getResourceString("network_diagnostics"));
            diagStage.setScene(new Scene(new VBox(new Text(getResourceString("network_diagnostics")), diagArea), 400, 300));
            diagStage.show();
        });
    }

    private String getResourceString(String key) {
        return java.util.ResourceBundle.getBundle("messages", java.util.Locale.getDefault()).getString(key);
    }

    private void notify(String message) {
        Platform.runLater(() -> System.out.println(message)); // UIManager로 전달
    }
}