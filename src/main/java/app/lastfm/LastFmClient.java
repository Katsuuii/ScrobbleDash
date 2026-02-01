package app.lastfm;

import app.models.RecentTrackRow;
import app.models.TopArtistRow;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public class LastFmClient {

    private static final String API_BASE = "https://ws.audioscrobbler.com/2.0/";
    // Last.fm "no image" placeholder hash (commonly returned)
    private static final String NO_IMAGE_HASH = "2a96cbd8b46e442fc41c2b86b821562f";

    private final HttpClient http;
    private final Gson gson;

    private final String apiKey;
    private final String username;

    public LastFmClient(String apiKey, String username) {
        this.http = HttpClient.newHttpClient();
        this.gson = new Gson();
        this.apiKey = requireNonBlank(apiKey, "api_key");
        this.username = requireNonBlank(username, "username");
    }

    public static LastFmClient fromClasspathProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream in = LastFmClient.class.getResourceAsStream("/lastfm.properties")) {
            if (in == null) throw new IOException("Missing /lastfm.properties in src/main/resources");
            props.load(in);
        }

        String apiKey = props.getProperty("api_key");
        String username = props.getProperty("username");
        return new LastFmClient(apiKey, username);
    }

    // -----------------------------
    // Paged wrapper
    // -----------------------------
    public static class PagedResult<T> {
        public final List<T> items;
        public final int page;
        public final int totalPages;
        public final int perPage;
        public final int total;

        public PagedResult(List<T> items, int page, int totalPages, int perPage, int total) {
            this.items = items;
            this.page = page;
            this.totalPages = totalPages;
            this.perPage = perPage;
            this.total = total;
        }
    }

    // -----------------------------
    // Recent Tracks (paged)
    // -----------------------------
    public PagedResult<RecentTrackRow> getRecentTracks(int limit, int page) throws IOException, InterruptedException {
        if (limit <= 0) limit = 50;
        if (page <= 0) page = 1;

        URI uri = buildRecentTracksUri(limit, page);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " from Last.fm: " + truncate(res.body(), 300));
        }

        RecentTracksResponse parsed = gson.fromJson(res.body(), RecentTracksResponse.class);
        if (parsed == null || parsed.recenttracks == null || parsed.recenttracks.track == null) {
            return new PagedResult<>(List.of(), page, 1, limit, 0);
        }

        int totalPages = safeInt(parsed.recenttracks.attr != null ? parsed.recenttracks.attr.totalPages : null, 1);
        int perPage = safeInt(parsed.recenttracks.attr != null ? parsed.recenttracks.attr.perPage : null, limit);
        int total = safeInt(parsed.recenttracks.attr != null ? parsed.recenttracks.attr.total : null, 0);
        int currentPage = safeInt(parsed.recenttracks.attr != null ? parsed.recenttracks.attr.page : null, page);

        List<RecentTrackRow> rows = new ArrayList<>();
        for (Track t : parsed.recenttracks.track) {
            String trackName = safe(t.name);
            String artist = pickArtistName(t.artist);
            String album = t.album != null ? safe(t.album.text) : "";

            boolean nowPlaying = t.attr != null && "true".equalsIgnoreCase(t.attr.nowplaying);

            Instant playedAt = null;
            if (!nowPlaying && t.date != null && t.date.uts != null && !t.date.uts.isBlank()) {
                playedAt = parseUts(t.date.uts);
            }

            // Album/track art (reliable)
            String imageUrl = pickBestImageUrl(t.image);

            // placeholder; controller renders relative time
            String when = nowPlaying ? "Now Playing" : "—";

            rows.add(new RecentTrackRow(trackName, artist, album, when, nowPlaying, playedAt, imageUrl));
        }

        return new PagedResult<>(rows, currentPage, totalPages, perPage, total);
    }

    private URI buildRecentTracksUri(int limit, int page) {
        String q = "method=" + enc("user.getrecenttracks")
                + "&user=" + enc(username)
                + "&api_key=" + enc(apiKey)
                + "&limit=" + enc(String.valueOf(limit))
                + "&page=" + enc(String.valueOf(page))
                + "&extended=1"
                + "&format=json";

        return URI.create(API_BASE + "?" + q);
    }

    // -----------------------------
    // Top Artists (paged)
    // -----------------------------
    public PagedResult<TopArtistRow> getTopArtists(String period, int limit, int page) throws IOException, InterruptedException {
        if (limit <= 0) limit = 50;
        if (page <= 0) page = 1;
        if (period == null || period.isBlank()) period = "7day";

        URI uri = buildTopArtistsUri(period, limit, page);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " from Last.fm: " + truncate(res.body(), 300));
        }

        TopArtistsResponse parsed = gson.fromJson(res.body(), TopArtistsResponse.class);
        if (parsed == null || parsed.topartists == null || parsed.topartists.artist == null) {
            return new PagedResult<>(List.of(), page, 1, limit, 0);
        }

        int totalPages = safeInt(parsed.topartists.attr != null ? parsed.topartists.attr.totalPages : null, 1);
        int perPage = safeInt(parsed.topartists.attr != null ? parsed.topartists.attr.perPage : null, limit);
        int total = safeInt(parsed.topartists.attr != null ? parsed.topartists.attr.total : null, 0);
        int currentPage = safeInt(parsed.topartists.attr != null ? parsed.topartists.attr.page : null, page);

        List<TopArtistRow> rows = new ArrayList<>();
        for (TopArtist a : parsed.topartists.artist) {
            String name = safe(a.name);
            int playcount = safeInt(a.playcount, 0);

            // Often blank or placeholder for artists
            String imageUrl = pickBestImageUrl(a.image);

            rows.add(new TopArtistRow(name, playcount, imageUrl));
        }

        return new PagedResult<>(rows, currentPage, totalPages, perPage, total);
    }

    private URI buildTopArtistsUri(String period, int limit, int page) {
        String q = "method=" + enc("user.gettopartists")
                + "&user=" + enc(username)
                + "&api_key=" + enc(apiKey)
                + "&period=" + enc(period)
                + "&limit=" + enc(String.valueOf(limit))
                + "&page=" + enc(String.valueOf(page))
                + "&format=json";

        return URI.create(API_BASE + "?" + q);
    }

    // -----------------------------
    // Artist image helpers (fallback chain)
    // -----------------------------

    /**
     * Try artist.getinfo for an artist image.
     * Can often be empty or "no image" placeholder.
     */
    public String getArtistImageUrl(String artistName) throws IOException, InterruptedException {
        if (artistName == null || artistName.isBlank()) return "";

        URI uri = buildArtistInfoUri(artistName);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return "";

        ArtistInfoResponse parsed = gson.fromJson(res.body(), ArtistInfoResponse.class);
        if (parsed == null || parsed.artist == null) return "";

        return pickBestImageUrl(parsed.artist.image);
    }

    private URI buildArtistInfoUri(String artistName) {
        String q = "method=" + enc("artist.getinfo")
                + "&artist=" + enc(artistName)
                + "&api_key=" + enc(apiKey)
                + "&autocorrect=1"
                + "&format=json";

        return URI.create(API_BASE + "?" + q);
    }

    /**
     * Fallback: fetch artist.gettopalbums&limit=1 and use the first album cover as the "artist icon".
     * This is usually MUCH more available than artist images.
     */
    public String getArtistTopAlbumImageUrl(String artistName) throws IOException, InterruptedException {
        if (artistName == null || artistName.isBlank()) return "";

        URI uri = buildArtistTopAlbumsUri(artistName);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return "";

        ArtistTopAlbumsResponse parsed = gson.fromJson(res.body(), ArtistTopAlbumsResponse.class);
        if (parsed == null || parsed.topalbums == null || parsed.topalbums.album == null || parsed.topalbums.album.isEmpty()) {
            return "";
        }

        TopAlbum first = parsed.topalbums.album.get(0);
        return pickBestImageUrl(first.image);
    }

    private URI buildArtistTopAlbumsUri(String artistName) {
        String q = "method=" + enc("artist.gettopalbums")
                + "&artist=" + enc(artistName)
                + "&api_key=" + enc(apiKey)
                + "&limit=1"
                + "&autocorrect=1"
                + "&format=json";

        return URI.create(API_BASE + "?" + q);
    }

    /**
     * Convenience method if you want a single call:
     * - try artist image
     * - if empty/placeholder -> try top album cover
     */
    public String getBestArtistIconUrl(String artistName) throws IOException, InterruptedException {
        String url = getArtistImageUrl(artistName);
        if (isBlankOrNoImage(url)) {
            url = getArtistTopAlbumImageUrl(artistName);
        }
        return isBlankOrNoImage(url) ? "" : url;
    }

    private static boolean isBlankOrNoImage(String url) {
        if (url == null || url.isBlank()) return true;
        return url.contains(NO_IMAGE_HASH);
    }

    // -----------------------------
    // Helpers
    // -----------------------------
    private static String pickArtistName(Artist a) {
        if (a == null) return "";
        if (a.name != null && !a.name.isBlank()) return a.name;
        if (a.text != null && !a.text.isBlank()) return a.text;
        return "";
    }

    private static Instant parseUts(String utsSeconds) {
        try {
            long secs = Long.parseLong(utsSeconds);
            return Instant.ofEpochSecond(secs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pickBestImageUrl(List<ImageInfo> images) {
        if (images == null || images.isEmpty()) return "";
        for (int i = images.size() - 1; i >= 0; i--) {
            ImageInfo it = images.get(i);
            if (it != null && it.text != null && !it.text.isBlank()) return it.text;
        }
        return "";
    }

    private static int safeInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    private static int safeInt(Object s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(String.valueOf(s).trim()); }
        catch (Exception e) { return def; }
    }

    private static String enc(String s) {
        return URLEncoder.encode(Objects.toString(s, ""), StandardCharsets.UTF_8);
    }

    private static String requireNonBlank(String v, String name) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing/blank property: " + name);
        }
        return v.trim();
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    // -----------------------------
    // Gson DTOs
    // -----------------------------
    private static class RecentTracksResponse {
        RecentTracks recenttracks;
    }

    private static class RecentTracks {
        List<Track> track;

        @SerializedName("@attr")
        PageAttr attr;
    }

    private static class Track {
        String name;
        Artist artist;
        Album album;
        DateInfo date;

        List<ImageInfo> image;

        @SerializedName("@attr")
        NowAttr attr;
    }

    private static class Artist {
        @SerializedName("#text")
        String text;

        String name;

        @SuppressWarnings("unused")
        String mbid;

        @SuppressWarnings("unused")
        String url;
    }

    private static class Album {
        @SerializedName("#text")
        String text;
    }

    private static class DateInfo {
        String uts;

        @SerializedName("#text")
        @SuppressWarnings("unused")
        String text;
    }

    private static class NowAttr {
        String nowplaying;
    }

    private static class PageAttr {
        String page;
        String perPage;
        String totalPages;
        String total;
    }

    private static class ImageInfo {
        @SerializedName("#text")
        String text;

        @SuppressWarnings("unused")
        String size;
    }

    // Top artists DTOs
    private static class TopArtistsResponse {
        TopArtists topartists;
    }

    private static class TopArtists {
        List<TopArtist> artist;

        @SerializedName("@attr")
        PageAttr attr;
    }

    private static class TopArtist {
        String name;
        String playcount;
        List<ImageInfo> image;
    }

    // Artist info DTOs
    private static class ArtistInfoResponse {
        ArtistInfo artist;
    }

    private static class ArtistInfo {
        List<ImageInfo> image;
    }

    // Artist top albums DTOs
    private static class ArtistTopAlbumsResponse {
        TopAlbums topalbums;
    }

    private static class TopAlbums {
        List<TopAlbum> album;
    }

    private static class TopAlbum {
        List<ImageInfo> image;
    }
}
