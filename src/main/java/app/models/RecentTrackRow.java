package app.models;

import java.time.Instant;

public class RecentTrackRow {
    private final String track;
    private final String artist;
    private final String album;

    // what you display in the "When" column (relative or "Now Playing")
    private final String when;

    private final boolean nowPlaying;

    // raw time for relative updates
    private final Instant playedAt;

    // album art URL (can be empty)
    private final String imageUrl;

    public RecentTrackRow(String track, String artist, String album,
                          String when, boolean nowPlaying,
                          Instant playedAt, String imageUrl) {
        this.track = track;
        this.artist = artist;
        this.album = album;
        this.when = when;
        this.nowPlaying = nowPlaying;
        this.playedAt = playedAt;
        this.imageUrl = imageUrl;
    }

    public String getTrack() { return track; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getWhen() { return when; }
    public boolean isNowPlaying() { return nowPlaying; }
    public Instant getPlayedAt() { return playedAt; }
    public String getImageUrl() { return imageUrl; }
}
