package app.models;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class RecentTrackRow {

    private final StringProperty track = new SimpleStringProperty("");
    private final StringProperty artist = new SimpleStringProperty("");
    private final StringProperty album = new SimpleStringProperty("");
    private final StringProperty when = new SimpleStringProperty("");
    private final BooleanProperty nowPlaying = new SimpleBooleanProperty(false);

    public RecentTrackRow(String track, String artist, String album, String when, boolean nowPlaying) {
        this.track.set(track);
        this.artist.set(artist);
        this.album.set(album);
        this.when.set(when);
        this.nowPlaying.set(nowPlaying);
    }

    // --- JavaFX properties (PropertyValueFactory works best with these)
    @SuppressWarnings("unused")
    public StringProperty trackProperty() { return track; }
    @SuppressWarnings("unused")
    public StringProperty artistProperty() { return artist; }
    @SuppressWarnings("unused")
    public StringProperty albumProperty() { return album;}
    public StringProperty whenProperty() { return when; }
    @SuppressWarnings("unused")
    public BooleanProperty nowPlayingProperty() { return nowPlaying; }

    // --- Also provide standard getters (nice to have)
    @SuppressWarnings("unused")
    public String getTrack() { return track.get(); }
    @SuppressWarnings("unused")
    public String getArtist() { return artist.get(); }
    @SuppressWarnings("unused")
    public String getAlbum() { return album.get(); }
    @SuppressWarnings("unused")
    public String getWhen() { return when.get(); }
    @SuppressWarnings("unused")
    public boolean isNowPlaying() { return nowPlaying.get(); }
}
