package app.lastfm;

import app.models.RecentTrackRow;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public class LastFmClient {

    private static final String API_BASE = "https://ws.audioscrobbler.com/2.0/";
    private static final DateTimeFormatter WHEN_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

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
            if (in == null) {
                throw new IOException("Missing /lastfm.properties in src/main/resources");
            }
            props.load(in);
        }

        String apiKey = props.getProperty("api_key");
        String username = props.getProperty("username");

        return new LastFmClient(apiKey, username);
    }

    public List<RecentTrackRow> getRecentTracks(int limit) throws IOException, InterruptedException {
        if (limit <= 0) limit = 50;

        URI uri = buildRecentTracksUri(limit);

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
            return List.of();
        }

        List<RecentTrackRow> rows = new ArrayList<>();
        for (Track t : parsed.recenttracks.track) {
            String trackName = safe(t.name);
            String artist = pickArtistName(t.artist);
            String album = t.album != null ? safe(t.album.text) : "";

            boolean nowPlaying = t.attr != null && "true".equalsIgnoreCase(t.attr.nowplaying);

            String when;
            if (nowPlaying) {
                when = "Now Playing";
            } else if (t.date != null && t.date.uts != null && !t.date.uts.isBlank()) {
                when = formatUts(t.date.uts);
            } else {
                when = "—";
            }

            rows.add(new RecentTrackRow(trackName, artist, album, when, nowPlaying));
        }

        return rows;
    }

    private URI buildRecentTracksUri(int limit) {
        // method=user.getrecenttracks
        // extended=1 returns artist.name (and other richer fields)
        String q = "method=" + enc("user.getrecenttracks")
                + "&user=" + enc(username)
                + "&api_key=" + enc(apiKey)
                + "&limit=" + enc(String.valueOf(limit))
                + "&extended=1"
                + "&format=json";

        return URI.create(API_BASE + "?" + q);
    }

    private static String pickArtistName(Artist a) {
        if (a == null) return "";
        if (a.name != null && !a.name.isBlank()) return a.name;
        if (a.text != null && !a.text.isBlank()) return a.text;
        return "";
    }

    private static String formatUts(String utsSeconds) {
        try {
            long secs = Long.parseLong(utsSeconds);
            return WHEN_FMT.format(Instant.ofEpochSecond(secs));
        } catch (NumberFormatException e) {
            return "—";
        }
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

    // ---- Gson DTOs ----

    private static class RecentTracksResponse {
        RecentTracks recenttracks;
    }

    private static class RecentTracks {
        List<Track> track;
    }

    private static class Track {
        String name;
        Artist artist;
        Album album;
        DateInfo date;

        @SerializedName("@attr")
        Attr attr;
    }

    private static class Artist {
        // Non-extended format uses "#text"
        @SerializedName("#text")
        String text;

        // Extended format uses "name"
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

    private static class Attr {
        String nowplaying;
    }
}
