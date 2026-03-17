package app;

import app.lastfm.LastFmClient;
import app.models.RecentTrackRow;
import app.models.TopArtistRow;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardController {

    private static final String NO_IMAGE_HASH = "2a96cbd8b46e442fc41c2b86b821562f";

    // ✅ Auto refresh defaults (always on)
    private static final int AUTO_REFRESH_DEFAULT_SECONDS = 20;

    // Artist icon cache + thread pool
    private final Map<String, String> artistImageCache = new ConcurrentHashMap<>();
    private final ExecutorService artistImagePool = Executors.newFixedThreadPool(4);
    private final Deque<RecentTrackRow> trackHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 100;
    // Tabs
    @FXML private TabPane tabs;

    // Recent tracks
    @FXML private TableView<RecentTrackRow> tracksTable;
    @FXML private TableColumn<RecentTrackRow, String> artCol;
    @FXML private TableColumn<RecentTrackRow, String> trackCol;
    @FXML private TableColumn<RecentTrackRow, String> artistCol;
    @FXML private TableColumn<RecentTrackRow, String> albumCol;
    @FXML private TableColumn<RecentTrackRow, String> whenCol;

    // Top artists
    @FXML private TableView<TopArtistRow> artistsTable;
    @FXML private TableColumn<TopArtistRow, String> artistArtCol;
    @FXML private TableColumn<TopArtistRow, String> topArtistCol;
    @FXML private TableColumn<TopArtistRow, Integer> playcountCol;

    // Controls
    @FXML private Button refreshButton;
    @FXML private Button loadMoreButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label statusLabel;

    // (You can keep these in FXML or remove them; auto-refresh works either way)
    @FXML private CheckBox autoRefreshCheck;
    @FXML private ComboBox<Integer> autoRefreshSeconds;

    // ✅ Now Playing bar (bottom)
    @FXML private HBox nowPlayingBar;
    @FXML private ImageView nowPlayingArt;
    @FXML private Label nowPlayingTitle;
    @FXML private Label nowPlayingArtist;
    @FXML private Label nowPlayingStatus;

    private LastFmClient client;

    // Recent tracks paging
    private int recentPage = 1;
    private int recentTotalPages = 1;
    private final int recentLimit = 50;

    // Busy flags
    private volatile boolean busyRecent = false;
    private volatile boolean busyArtists = false;

    private Timeline autoRefreshTimeline;
    private Timeline relativeTimeTick;

    @FXML
    private void initialize() {

        // ---- Recent tracks table ----
        artCol.setCellValueFactory(new PropertyValueFactory<>("imageUrl"));
        trackCol.setCellValueFactory(new PropertyValueFactory<>("track"));
        artistCol.setCellValueFactory(new PropertyValueFactory<>("artist"));
        albumCol.setCellValueFactory(new PropertyValueFactory<>("album"));
        whenCol.setCellValueFactory(new PropertyValueFactory<>("when"));
        tracksTable.setItems(FXCollections.observableArrayList());

        // Recent track album art cell
        artCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView iv = new ImageView();
            {
                iv.setFitWidth(38);
                iv.setFitHeight(38);
                iv.setPreserveRatio(true);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override
            protected void updateItem(String url, boolean empty) {
                super.updateItem(url, empty);
                if (empty || url == null || url.isBlank()) {
                    setGraphic(null);
                    return;
                }
                iv.setImage(new Image(url, true));
                setGraphic(iv);
            }
        });

        // Recent relative time
        whenCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String ignored, boolean empty) {
                super.updateItem(ignored, empty);
                if (empty || getTableRow() == null) {
                    setText(null);
                    return;
                }
                RecentTrackRow row = getTableRow().getItem();
                if (row == null) {
                    setText(null);
                    return;
                }
                setText(row.isNowPlaying() ? "Now Playing" : formatRelative(row.getPlayedAt()));
            }
        });

        // ---- Top artists table ----
        artistArtCol.setCellValueFactory(new PropertyValueFactory<>("imageUrl"));
        topArtistCol.setCellValueFactory(new PropertyValueFactory<>("artist"));
        playcountCol.setCellValueFactory(new PropertyValueFactory<>("playcount"));
        artistsTable.setItems(FXCollections.observableArrayList());

        // ✅ Top artist art cell: CACHE FIRST finalUrl
        artistArtCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView iv = new ImageView();
            {
                iv.setFitWidth(32);
                iv.setFitHeight(32);
                iv.setPreserveRatio(true);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }

            @Override
            protected void updateItem(String url, boolean empty) {
                super.updateItem(url, empty);

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }

                TopArtistRow row = getTableRow().getItem();
                String artistName = row.getArtist();

                // CACHE FIRST
                String cached = (artistName == null) ? "" : artistImageCache.getOrDefault(artistName, "");
                String finalUrl = cached;

                // If cache missing, use the row url (from getTopArtists)
                if (finalUrl.isBlank() && url != null && !url.isBlank()) {
                    finalUrl = url;
                }

                // Ignore placeholder
                if (finalUrl.contains(NO_IMAGE_HASH)) {
                    finalUrl = "";
                }

                if (finalUrl.isBlank()) {
                    setGraphic(null);
                    return;
                }

                iv.setImage(new Image(finalUrl, true));
                setGraphic(iv);
            }
        });

        // ---- Auto refresh UI (optional to keep) ----
        if (autoRefreshSeconds != null) {
            autoRefreshSeconds.setItems(FXCollections.observableArrayList(15, 20, 30, 60, 120));
            autoRefreshSeconds.getSelectionModel().select(Integer.valueOf(AUTO_REFRESH_DEFAULT_SECONDS));
            autoRefreshSeconds.setDisable(false);

            autoRefreshSeconds.valueProperty().addListener((obs, o, n) -> configureAutoRefresh());
        }

        if (autoRefreshCheck != null) {
            // Always-on: keep it checked and enabled, but we don't rely on it.
            autoRefreshCheck.setSelected(true);
            autoRefreshCheck.setDisable(false);

            autoRefreshCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if(newVal){
                configureAutoRefresh();
                statusLabel.setText("Auto-Refresh Enabled.");
            }else {
                if(autoRefreshTimeline != null){
                    autoRefreshTimeline.stop(); // Stops the timer
                }
                statusLabel.setText("Auto-Refresh Disabled.");
            }
            });
        }

        // Refresh relative time every 20s
        relativeTimeTick = new Timeline(new KeyFrame(Duration.seconds(20), e -> tracksTable.refresh()));
        relativeTimeTick.setCycleCount(Timeline.INDEFINITE);
        relativeTimeTick.play();

        // Now Playing bar hidden initially
        if (nowPlayingBar != null) {
            nowPlayingBar.setVisible(false);
            nowPlayingBar.setManaged(false);
        }

        loadMoreButton.setDisable(true);

        // Load API config
        try {
            client = LastFmClient.fromClasspathProperties();
            statusLabel.setText("Loaded configuration. Auto-refresh is ON.");

            // ✅ Always start auto-refresh + do first load automatically
            configureAutoRefresh();
            Platform.runLater(this::refreshAll);

        } catch (Exception e) {
            statusLabel.setText("Config error: " + e.getMessage());
            refreshButton.setDisable(true);
            loadMoreButton.setDisable(true);
        }
    }

    @FXML
    private void onRefresh() {
        refreshAll();
    }

    @FXML
    private void onLoadMore() {
        loadMoreRecent();
    }

    private void refreshAll() {
        if (client == null || busyRecent || busyArtists) return;

        // Remove tracksTable.getItems().clear();  <-- REMOVE OR COMMENT THIS OUT

        recentPage = 1;
        recentTotalPages = 1;

        loadMoreButton.setDisable(true);
        loadRecentPage(recentPage, true); // This will now "diff" the data instead of replacing
        loadTopArtists();
    }

    private void loadMoreRecent() {
        if (client == null || busyRecent || recentPage >= recentTotalPages) return;
        loadRecentPage(recentPage + 1, false);
    }

    // -----------------------------
    // RECENT TRACKS (PAGINATION)
    // -----------------------------
    private void loadRecentPage(int page, boolean replace) {
        if (client == null || busyRecent) return;

        busyRecent = true;
        progress.setVisible(true);

        Task<LastFmClient.PagedResult<RecentTrackRow>> task = new Task<>() {
            @Override
            protected LastFmClient.PagedResult<RecentTrackRow> call() throws Exception {
                return client.getRecentTracks(recentLimit, page);
            }
        };

        task.setOnSucceeded(e -> {
            var res = task.getValue();
            recentPage = res.page;
            recentTotalPages = Math.max(1, res.totalPages);

            if (replace) {
                // --- 1. PERFORMANCE ANALYSIS START ---
                long startTime = System.nanoTime();
                long startMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

                // --- 2. DATA PROCESSING (The "Sliding Window" Logic) ---
                List<RecentTrackRow> incoming = res.items;
                List<RecentTrackRow> toAdd = new ArrayList<>();

                for (RecentTrackRow track : incoming) {
                    // Deduplication check
                    boolean exists = trackHistory.stream()
                            .anyMatch(existing -> isSameScrobble(track, existing));

                    if (exists) break; // Found data already in history, stop scanning
                    toAdd.add(track);
                }

                // Add the newest items to the top (LIFO)
                Collections.reverse(toAdd);
                for (RecentTrackRow newRow : toAdd) {
                    // Clean up old "Now Playing" placeholders if they match the new scrobble
                    trackHistory.removeIf(r -> r.getTrack().equals(newRow.getTrack()) && r.isNowPlaying());
                    tracksTable.getItems().removeIf(r -> r.getTrack().equals(newRow.getTrack()) && r.isNowPlaying());

                    trackHistory.addFirst(newRow);
                    tracksTable.getItems().add(0, newRow);
                }

                // Enforce K=100 (FIFO removal)
                while (trackHistory.size() > MAX_HISTORY) {
                    trackHistory.removeLast();
                    tracksTable.getItems().remove(tracksTable.getItems().size() - 1);
                }

                // --- 3. PERFORMANCE ANALYSIS END ---
                long endTime = System.nanoTime();
                long endMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

                // Console Output for the Data
                System.out.println("--- ScrobbleDash Performance Stats ---");
                System.out.println("Processing Time: " + (endTime - startTime) / 1_000_000.0 + " ms");
                System.out.println("Memory Footprint: " + (endMem / (1024.0 * 1024.0)) + " MB");
                System.out.println("Current History Size: " + trackHistory.size());
                System.out.println("----------------------------------------");
            }

            // Update UI components
            updateNowPlayingBar();
            tracksTable.refresh();

            progress.setVisible(false);
            refreshButton.setDisable(false);
            loadMoreButton.setDisable(recentPage >= recentTotalPages);
            busyRecent = false;
        });

        new Thread(task).start();
    }

    private void loadTopArtists() {
        if (client == null || busyArtists) return;

        busyArtists = true;
        progress.setVisible(true);

        Task<List<TopArtistRow>> task = new Task<>() {
            @Override
            protected List<TopArtistRow> call() throws Exception {
                return client.getTopArtists("7day", 50, 1).items;
            }
        };

        task.setOnSucceeded(e -> {
            List<TopArtistRow> rows = task.getValue();
            artistsTable.getItems().setAll(rows);

            // Fetch icons in background
            for (TopArtistRow r : rows) {
                String artistName = r.getArtist();
                if (artistName == null || artistName.isBlank()) continue;

                String baseUrl = r.getImageUrl();
                boolean hasRealUrl = baseUrl != null && !baseUrl.isBlank() && !baseUrl.contains(NO_IMAGE_HASH);
                if (hasRealUrl) continue;

                if (artistImageCache.containsKey(artistName)) continue;

                artistImagePool.submit(() -> {
                    try {
                        String iconUrl = client.getBestArtistIconUrl(artistName);
                        if (iconUrl == null) iconUrl = "";

                        if (iconUrl.isBlank() || iconUrl.contains(NO_IMAGE_HASH)) return;

                        artistImageCache.put(artistName, iconUrl);
                        Platform.runLater(() -> artistsTable.refresh());
                    } catch (Exception ignored) { }
                });
            }

            progress.setVisible(false);
            busyArtists = false;
        });

        task.setOnFailed(e -> {
            progress.setVisible(false);
            busyArtists = false;
        });

        new Thread(task, "lastfm-topartists").start();
    }

    // -----------------------------
    // ✅ ALWAYS-ON AUTO REFRESH
    // -----------------------------
    private void configureAutoRefresh() {
        // Stop any existing timer first
        if (autoRefreshTimeline != null) {
            autoRefreshTimeline.stop();
        }

        // CHECK: If the user unchecked the box, don't start a new timer
        if (autoRefreshCheck != null && !autoRefreshCheck.isSelected()) {
            return;
        }

        int secs = AUTO_REFRESH_DEFAULT_SECONDS;
        if (autoRefreshSeconds != null) {
            Integer v = autoRefreshSeconds.getValue();
            if (v != null && v >= 5) secs = v;
        }

        autoRefreshTimeline = new Timeline(
                new KeyFrame(Duration.seconds(secs), e -> {
                    if (!busyRecent && !busyArtists) refreshAll();
                })
        );
        autoRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        autoRefreshTimeline.play();
    }
    // -----------------------------
    // ✅ NOW PLAYING BAR (BOTTOM)
    // -----------------------------
    private void updateNowPlayingBar() {
        if (nowPlayingBar == null) return;

        RecentTrackRow now = null;
        for (RecentTrackRow r : tracksTable.getItems()) {
            if (r != null && r.isNowPlaying()) {
                now = r;
                break;
            }
        }

        if (now == null) {
            nowPlayingBar.setVisible(false);
            nowPlayingBar.setManaged(false);
            return;
        }

        nowPlayingTitle.setText(now.getTrack());
        nowPlayingArtist.setText(now.getArtist());
        nowPlayingStatus.setText("Now Playing");

        String artUrl = now.getImageUrl();
        if (artUrl != null && !artUrl.isBlank()) {
            nowPlayingArt.setImage(new Image(artUrl, true));
        } else {
            nowPlayingArt.setImage(null);
        }

        nowPlayingBar.setManaged(true);
        nowPlayingBar.setVisible(true);
    }

    private static String formatRelative(Instant playedAt) {
        if (playedAt == null) return "—";
        long s = Instant.now().getEpochSecond() - playedAt.getEpochSecond();
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    //-------------------------------
    //✅ SAME SCROBBLE
    //-------------------------------

    private boolean isSameScrobble(RecentTrackRow fetched, RecentTrackRow existing) {
        if (fetched == null || existing == null) return false;

        // If it's currently playing, we treat it as "not permanent"
        // so it gets refreshed every cycle.
        if (fetched.isNowPlaying() || existing.isNowPlaying()) return false;

        // Use the timestamp for 100% accuracy
        if (fetched.getPlayedAt() != null && existing.getPlayedAt() != null) {
            return fetched.getPlayedAt().equals(existing.getPlayedAt()) &&
                    fetched.getTrack().equalsIgnoreCase(existing.getTrack());
        }
        return false;
    }

}
