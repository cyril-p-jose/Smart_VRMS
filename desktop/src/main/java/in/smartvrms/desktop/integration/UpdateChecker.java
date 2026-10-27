package in.smartvrms.desktop.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smartvrms.desktop.infrastructure.AppVersion;
import java.net.URI;
import java.net.http.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Checks the public GitHub Releases feed, including prereleases, without credentials or silent downloads. */
public final class UpdateChecker {
    public static Optional<URI> newerRelease(String current, String location) {
        if (location == null) return Optional.empty();
        try {
            URI uri = URI.create(location);
            String prefix = "/cyril-p-jose/Smart_VRMS/releases/tag/";
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().startsWith(prefix)) return Optional.empty();
            String tag = uri.getRawPath().substring(prefix.length());
            return compareVersions(current, tag) < 0 ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException ex) { return Optional.empty(); }
    }

    /** Parses the public release list and returns the highest valid published version newer than current. */
    public static Optional<URI> newerReleaseFromJson(String current, String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) throw new IllegalArgumentException("Release response is not an array.");
        JsonArray releases = root.getAsJsonArray();
        List<String> newerTags = new ArrayList<>();
        for (JsonElement element : releases) {
            if (!element.isJsonObject()) continue;
            JsonObject release = element.getAsJsonObject();
            if (bool(release, "draft")) continue;
            String tag = string(release, "tag_name");
            if (tag == null || compareVersionsOrEqualInvalid(current, tag) == null) continue;
            if (compareVersions(current, tag) < 0) newerTags.add(tag);
        }
        return newerTags.stream()
                .max(Comparator.comparing(tag -> new Version(tag)))
                .map(tag -> URI.create("https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/" + tag));
    }

    public Optional<URI> check() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        URI releasesApi = URI.create("https://api.github.com/repos/cyril-p-jose/Smart_VRMS/releases?per_page=100");
        HttpRequest request = HttpRequest.newBuilder(releasesApi).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Smart-VRMS-Update-Checker")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IllegalStateException("Update check unavailable.");
        byte[] body = response.body();
        if (body.length > 1_048_576) throw new IllegalStateException("Release response is too large.");
        return newerReleaseFromJson(AppVersion.CURRENT, new String(body, StandardCharsets.UTF_8));
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static Integer compareVersionsOrEqualInvalid(String current, String candidate) {
        try { return compareVersions(current, candidate); }
        catch (IllegalArgumentException ex) { return null; }
    }

    /** Returns negative when candidate is older, zero when equal, positive when candidate is newer. */
    private static int compareVersions(String current, String candidate) {
        Version left = new Version(current);
        Version right = new Version(candidate);
        for (int i = 0; i < 3; i++) {
            int c = left.core[i].compareTo(right.core[i]);
            if (c != 0) return c;
        }
        if (left.prerelease == null && right.prerelease == null) return 0;
        if (left.prerelease == null) return 1;
        if (right.prerelease == null) return -1;
        int size = Math.min(left.prerelease.length, right.prerelease.length);
        for (int i = 0; i < size; i++) {
            String a = left.prerelease[i], b = right.prerelease[i];
            if (a.equals(b)) continue;
            boolean an = a.matches("0|[1-9][0-9]*"), bn = b.matches("0|[1-9][0-9]*");
            if (an && bn) return new BigInteger(a).compareTo(new BigInteger(b));
            if (an != bn) return an ? -1 : 1;
            return a.compareTo(b);
        }
        return Integer.compare(left.prerelease.length, right.prerelease.length);
    }

    private static final class Version implements Comparable<Version> {
        private final BigInteger[] core;
        private final String[] prerelease;
        private Version(String value) {
            if (value == null) throw new IllegalArgumentException("Missing version.");
            String normalized = value.startsWith("v") ? value.substring(1) : value;
            if (!normalized.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"))
                throw new IllegalArgumentException("Invalid release version.");
            String[] parts = normalized.split("-", 2);
            String[] numbers = parts[0].split("\\.");
            this.core = new BigInteger[]{new BigInteger(numbers[0]), new BigInteger(numbers[1]), new BigInteger(numbers[2])};
            this.prerelease = parts.length == 2 ? parts[1].split("\\.") : null;
        }
        @Override public int compareTo(Version other) {
            // Reuse the comparator and keep ordering for the release list deterministic.
            return compareVersions(toString(), other.toString());
        }
        @Override public String toString() {
            return core[0] + "." + core[1] + "." + core[2] + (prerelease == null ? "" : "-" + String.join(".", prerelease));
        }
    }
}
