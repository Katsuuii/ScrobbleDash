package app;

import app.lastfm.LastFmClient;
import app.models.RecentTrackRow;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class DashboardController {

    @FXML private TableView<RecentTrackRow> tracksTable;

    @FXML private TableColumn<RecentTrackRow, String> trackCol;
    @FXML private TableColumn<RecentTrackRow, String> artistCol;
    @FXML private TableColumn<RecentTrackRow, String> albumCol;
    @FXML private TableColumn<RecentTrackRow, String> whenCol;

    @FXML private Button refreshButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label statusLabel;

    private LastFmClient client;

    @FXML
    private void initialize() {
        // Bind columns to RecentTrackRow getters (property names)
        trackCol.setCellValueFactory(new PropertyValueFactory<>("track"));
        artistCol.setCellValueFactory(new PropertyValueFactory<>("artist"));
        albumCol.setCellValueFactory(new PropertyValueFactory<>("album"));
        whenCol.setCellValueFactory(new PropertyValueFactory<>("when"));

        tracksTable.setItems(FXCollections.observableArrayList());

        try {
            client = LastFmClient.fromClasspathProperties();
            statusLabel.setText("Loaded configuration. Click Refresh.");
        } catch (Exception e) {
            statusLabel.setText("Config error: " + e.getMessage());
            refreshButton.setDisable(true);
        }
    }

    @FXML
    private void onRefresh() {
        refreshRecentTracks();
    }

    private void refreshRecentTracks() {
        if (client == null) return;

        refreshButton.setDisable(true);
        progress.setVisible(true);
        statusLabel.setText("Fetching recent tracks...");

        Instant started = Instant.now();

        Task<List<RecentTrackRow>> task = new Task<>() {
            @Override
            protected List<RecentTrackRow> call() throws Exception {
                // MVP: fetch last 50
                return client.getRecentTracks(100);
            }
        };

        task.setOnSucceeded(evt -> {
            List<RecentTrackRow> rows = task.getValue();
            tracksTable.getItems().setAll(rows);

            long ms = Duration.between(started, Instant.now()).toMillis();
            statusLabel.setText("Loaded " + rows.size() + " tracks in " + ms + " ms.");
            progress.setVisible(false);
            refreshButton.setDisable(false);
        });

        task.setOnFailed(evt -> {
            Throwable ex = task.getException();
            statusLabel.setText("Fetch failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
            progress.setVisible(false);
            refreshButton.setDisable(false);
        });

        Thread t = new Thread(task, "lastfm-refresh");
        t.setDaemon(true);
        t.start();
    }
}
