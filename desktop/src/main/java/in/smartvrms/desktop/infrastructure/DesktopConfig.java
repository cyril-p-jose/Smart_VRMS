package in.smartvrms.desktop.infrastructure;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Public desktop configuration (NFR-DIST-002, NFR-SEC-001).
 *
 * Resolves user-writable platform configuration directories outside the installed binary folder.
 * Stores public endpoint only; zero credentials, secrets, or session tokens are ever written to disk.
 */
public record DesktopConfig(Optional<URI> apiEndpoint) {

    /**
     * Resolves the user-writable platform configuration directory.
     * Windows: %LOCALAPPDATA%\SmartVRMS
     * macOS: ~/Library/Application Support/SmartVRMS
     * Linux: $XDG_CONFIG_HOME/smart-vrms (or ~/.config/smart-vrms)
     */
    public static Path getPlatformConfigDirectory() {
        String override = System.getProperty("smartvrms.config.dir");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "SmartVRMS");
            }
            return Path.of(userHome, "AppData", "Local", "SmartVRMS");
        } else if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "SmartVRMS");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            if (xdg != null && !xdg.isBlank()) {
                return Path.of(xdg, "smart-vrms");
            }
            return Path.of(userHome, ".config", "smart-vrms");
        }
    }

    /**
     * Loads desktop configuration in priority order:
     * 1. User-specific platform configuration: <PlatformConfigDir>/config.properties
     * 2. Local workspace configuration: local-config/desktop.properties
     * 3. Environment variable: SMART_VRMS_API_URL
     */
    public static DesktopConfig load() {
        // 1. Check user-writable platform directory
        Path userConfig = getPlatformConfigDirectory().resolve("config.properties");
        if (Files.exists(userConfig)) {
            try (FileInputStream in = new FileInputStream(userConfig.toFile())) {
                Properties props = new Properties();
                props.load(in);
                String val = props.getProperty("api.endpoint", props.getProperty("smart.vrms.api.url"));
                if (val != null && !val.isBlank()) {
                    return fromEndpoint(val);
                }
            } catch (IOException ignored) { }
        }

        // 2. Check local workspace config
        Path localConfig = Path.of("local-config/desktop.properties");
        if (Files.exists(localConfig)) {
            try (FileInputStream in = new FileInputStream(localConfig.toFile())) {
                Properties props = new Properties();
                props.load(in);
                String val = props.getProperty("api.endpoint", props.getProperty("smart.vrms.api.url"));
                if (val != null && !val.isBlank()) {
                    return fromEndpoint(val);
                }
            } catch (IOException ignored) { }
        }

        // 3. Check environment variable
        String envVal = System.getenv("SMART_VRMS_API_URL");
        if (envVal != null && !envVal.isBlank()) {
            return fromEndpoint(envVal);
        }

        try(var bundled=DesktopConfig.class.getResourceAsStream("/default-api.properties")) {
            if(bundled!=null){Properties defaults=new Properties();defaults.load(bundled);String url=defaults.getProperty("api.endpoint");if(url!=null&&!url.isBlank())return fromEndpoint(url);}
        } catch(IOException ex) { throw new IllegalArgumentException("Could not read bundled API configuration."); }
        return new DesktopConfig(Optional.empty());
    }

    /**
     * Persists the public API endpoint to the user-writable platform directory.
     * Does not store passwords, tokens, or credentials.
     */
    public static void saveEndpoint(URI endpoint) throws IOException {
        if (endpoint == null) return;
        fromEndpoint(endpoint.toString());
        Path dir = getPlatformConfigDirectory();
        Files.createDirectories(dir);
        Path configFile = dir.resolve("config.properties");
        Properties props = new Properties();
        if (Files.exists(configFile)) {
            try (FileInputStream in = new FileInputStream(configFile.toFile())) {
                props.load(in);
            } catch (IOException ignored) { }
        }
        props.setProperty("api.endpoint", endpoint.toString());
        try (FileOutputStream out = new FileOutputStream(configFile.toFile())) {
            props.store(out, "Smart VRMS Client Configuration - Public API Endpoint Only");
        }
    }

    public static DesktopConfig fromEndpoint(String value) {
        if (value == null || value.isBlank()) return new DesktopConfig(Optional.empty());
        final URI uri;
        try { uri = URI.create(value.strip()); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("API endpoint must be a valid HTTPS URL."); }
        boolean isLoopbackHttp = "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
        boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());
        if ((!isHttps && !isLoopbackHttp) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("API endpoint requires HTTPS (or explicit 127.0.0.1 loopback HTTP for local development) and must not contain credentials, query or fragment.");
        }
        return new DesktopConfig(Optional.of(uri));
    }
}
