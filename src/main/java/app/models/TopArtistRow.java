package app.models;

public class TopArtistRow {
    private final String artist;
    private final int playcount;
    private final String imageUrl;

    public TopArtistRow(String artist, int playcount, String imageUrl) {
        this.artist = artist;
        this.playcount = playcount;
        this.imageUrl = imageUrl;
    }

    public String getArtist() { return artist; }
    public int getPlaycount() { return playcount; }
    public String getImageUrl() { return imageUrl; }
}
