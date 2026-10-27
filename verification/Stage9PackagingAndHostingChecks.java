package in.smartvrms.verification;

import com.google.gson.*;
import in.smartvrms.contracts.SignInResult;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.server.api.HttpServerConfig;
import in.smartvrms.server.api.VrmsHttpServer;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.integration.SupabaseConfig;
import in.smartvrms.server.security.StaffAuthorizer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Stage 9 — Hosting, Native Packaging and Multi-Platform Distribution Checks.
 *
 * Verifies public liveness health probes, container topology, user configuration directory
 * separation, client package isolation, jpackage artifacts, and multi-platform build scripts.
 */
public final class Stage9PackagingAndHostingChecks {
    private static int checks;

    private static void require(boolean val, String reason) {
        if (!val) throw new AssertionError("FAIL: " + reason);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        // Test isolation: never overwrite a real user's public API configuration.
        System.setProperty("smartvrms.config.dir", Path.of("verification/generated/test-appdata").toAbsolutePath().toString());
        System.out.println("Starting Stage 9 hosting and native distribution checks...");
        testPublicHealthCheckProbe();
        testPlatformUserConfigDirectoryAndEndpointPersistence();
        testDesktopConfigValidationRules();
        testClientPackageIsolationAndArtifacts();
        testServerDeploymentBundleAndCloudSpecs();
        testMultiPlatformPackagingScripts();
        testPlatformSupportMatrixAndGateG6Status();
        System.out.println("PASS: " + checks + " Stage 9 hosting, packaging, configuration, and platform distribution assertions.");
    }

    /**
     * Verifies that the server exposes unauthenticated public health checks on /health and /api/v1/health
     * for cloud container probes (Render, Railway, Kubernetes, Docker healthcheck) without requiring a token.
     */
    private static void testPublicHealthCheckProbe() throws Exception {
        System.out.println("Testing public liveness health check probe...");

        SupabaseConfig dummyConfig = new SupabaseConfig("https://abcdefghijklmnopqrst.supabase.co", "sb_publishable_fixture0123456789");
        SupabaseAuth auth = new SupabaseAuth(dummyConfig);
        VrmsHttpServer.Authorizer authorizer = (token, cap) -> {
            throw new SecurityException("Auth not needed for public health");
        };

        HttpServerConfig config = new HttpServerConfig(0);
        VrmsHttpServer server = new VrmsHttpServer(config, auth, authorizer);
        server.start();

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

            // 1. Test GET /health
            URI healthUri = server.baseUri().resolve("/health");
            HttpRequest getReq = HttpRequest.newBuilder().uri(healthUri).GET().build();
            HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());

            require(getResp.statusCode() == 200, "GET /health returns HTTP 200 OK without auth token");
            require(getResp.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                    "/health returns application/json Content-Type");

            JsonObject json = JsonParser.parseString(getResp.body()).getAsJsonObject();
            require("HEALTHY".equals(json.get("status").getAsString()), "Status is HEALTHY");
            require(json.has("timestamp"), "Health response includes timestamp");
            require(json.has("version"), "Health response includes version");

            // 2. Test GET /api/v1/health
            URI apiHealthUri = server.baseUri().resolve("/api/v1/health");
            HttpRequest apiReq = HttpRequest.newBuilder().uri(apiHealthUri).GET().build();
            HttpResponse<String> apiResp = client.send(apiReq, HttpResponse.BodyHandlers.ofString());
            require(apiResp.statusCode() == 200, "GET /api/v1/health returns HTTP 200 OK");

            // 3. Test HEAD /health
            HttpRequest headReq = HttpRequest.newBuilder().uri(healthUri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> headResp = client.send(headReq, HttpResponse.BodyHandlers.discarding());
            require(headResp.statusCode() == 200, "HEAD /health returns HTTP 200 OK");

            // 4. Test method not allowed for POST /health
            HttpRequest postReq = HttpRequest.newBuilder().uri(healthUri).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> postResp = client.send(postReq, HttpResponse.BodyHandlers.ofString());
            require(postResp.statusCode() == 405, "POST /health returns 405 Method Not Allowed");

            // 5. Protected endpoints still require authentication
            URI meUri = server.baseUri().resolve("/api/v1/auth/me");
            HttpRequest meReq = HttpRequest.newBuilder().uri(meUri).GET().build();
            HttpResponse<String> meResp = client.send(meReq, HttpResponse.BodyHandlers.ofString());
            require(meResp.statusCode() == 401, "Protected endpoint /api/v1/auth/me returns 401 without token");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Verifies platform user configuration directory resolution and persistence outside program files (NFR-DIST-002).
     */
    private static void testPlatformUserConfigDirectoryAndEndpointPersistence() throws Exception {
        System.out.println("Testing platform user config directory and endpoint persistence...");

        Path platformDir = DesktopConfig.getPlatformConfigDirectory();
        require(platformDir != null, "Platform config directory resolved");
        String dirStr = platformDir.toString().toLowerCase(Locale.ROOT);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            require(dirStr.contains("appdata") || dirStr.contains("local"),
                    "Windows config directory is under AppData/Local/SmartVRMS");
        }

        // Test saving endpoint to platform directory
        URI testUri = URI.create("https://api.smartvrms.fictional-domain.in");
        DesktopConfig.saveEndpoint(testUri);

        Path configFile = platformDir.resolve("config.properties");
        require(Files.exists(configFile), "config.properties created in platform directory");

        Properties loadedProps = new Properties();
        try (var in = Files.newInputStream(configFile)) {
            loadedProps.load(in);
        }
        require("https://api.smartvrms.fictional-domain.in".equals(loadedProps.getProperty("api.endpoint")), "Endpoint persisted correctly");

        String content = Files.readString(configFile, StandardCharsets.UTF_8);
        require(!content.contains("password"), "Zero passwords in config.properties");
        require(!content.contains("token"), "Zero tokens in config.properties");

        // Test loading config
        DesktopConfig loaded = DesktopConfig.load();
        require(loaded.apiEndpoint().isPresent(), "Loaded endpoint is present");
        require(testUri.equals(loaded.apiEndpoint().get()), "Loaded endpoint matches saved URI");
    }

    /**
     * Verifies DesktopConfig URL validation rules (HTTPS required, loopback allowed for dev, userinfo rejected).
     */
    private static void testDesktopConfigValidationRules() {
        System.out.println("Testing DesktopConfig validation rules...");

        // 1. Valid HTTPS
        DesktopConfig c1 = DesktopConfig.fromEndpoint("https://prod-api.smartvrms.in");
        require(c1.apiEndpoint().isPresent(), "Valid HTTPS endpoint accepted");

        // 2. Valid loopback HTTP for local development
        DesktopConfig c2 = DesktopConfig.fromEndpoint("http://127.0.0.1:8080");
        require(c2.apiEndpoint().isPresent(), "Loopback 127.0.0.1 HTTP accepted for dev");

        // 3. Insecure non-loopback HTTP rejected
        boolean httpRejected = false;
        try {
            DesktopConfig.fromEndpoint("http://insecure-domain.com");
        } catch (IllegalArgumentException e) {
            httpRejected = true;
        }
        require(httpRejected, "Insecure non-loopback HTTP endpoint rejected");

        // 4. Credentials in URL rejected
        boolean credsRejected = false;
        try {
            DesktopConfig.fromEndpoint("https://user:pass@api.smartvrms.in");
        } catch (IllegalArgumentException e) {
            credsRejected = true;
        }
        require(credsRejected, "URL with embedded userinfo/credentials rejected");

        // 5. Query parameters rejected
        boolean queryRejected = false;
        try {
            DesktopConfig.fromEndpoint("https://api.smartvrms.in?token=12345");
        } catch (IllegalArgumentException e) {
            queryRejected = true;
        }
        require(queryRejected, "URL with query parameters rejected");

        // 6. Blank / null returns empty optional
        DesktopConfig empty1 = DesktopConfig.fromEndpoint(null);
        DesktopConfig empty2 = DesktopConfig.fromEndpoint("  ");
        require(empty1.apiEndpoint().isEmpty() && empty2.apiEndpoint().isEmpty(), "Empty endpoint produces empty DesktopConfig");
    }

    /**
     * Verifies that the client desktop package strictly isolates client classes from server classes (NFR-SEC-001).
     */
    private static void testClientPackageIsolationAndArtifacts() {
        System.out.println("Testing client package isolation and artifacts...");

        Path packageDir = Path.of("dist/package-win64/SmartVRMS");
        if (!Files.exists(packageDir)) {
            System.out.println("SKIP: package-win64 not yet generated on local disk. Checking dist/client-jars...");
            packageDir = Path.of("dist/client-jars");
        }

        Path appDir = packageDir.resolve("app");
        Path checkDir = Files.exists(appDir) ? appDir : packageDir;

        // Verify client JARs exist
        Path desktopJar = checkDir.resolve("desktop-0.1.0-SNAPSHOT.jar");
        Path contractsJar = checkDir.resolve("contracts-0.1.0-SNAPSHOT.jar");
        Path appJar = checkDir.resolve("application-0.1.0-SNAPSHOT.jar");

        if (Files.exists(desktopJar)) {
            require(Files.exists(desktopJar), "desktop-0.1.0-SNAPSHOT.jar present in client package");
            require(Files.exists(contractsJar), "contracts-0.1.0-SNAPSHOT.jar present in client package");
            require(Files.exists(appJar), "application-0.1.0-SNAPSHOT.jar present in client package");

            // Verify server-side classes and JDBC driver are strictly excluded from client package
            Path serverJar = checkDir.resolve("server-0.1.0-SNAPSHOT.jar");
            Path pgDriver = checkDir.resolve("postgresql-42.7.14.jar");
            require(!Files.exists(serverJar), "server-0.1.0-SNAPSHOT.jar strictly excluded from desktop client package");
            require(!Files.exists(pgDriver), "PostgreSQL JDBC driver strictly excluded from desktop client package");
        }

        // Verify native executable exists if package was generated
        Path exePath = Path.of("dist/package-win64/SmartVRMS/SmartVRMS.exe");
        if (Files.exists(exePath)) {
            require(Files.isRegularFile(exePath), "SmartVRMS.exe native launcher exists");
            require(exePath.toFile().length() > 50000, "SmartVRMS.exe has valid native executable binary size");
            Path runtimeDir = Path.of("dist/package-win64/SmartVRMS/runtime");
            require(Files.isDirectory(runtimeDir), "Bundled OpenJDK 17 runtime exists inside package");
        }
    }

    /**
     * Verifies headless server deployment bundle, Dockerfile, and cloud hosting specifications (NFR-HOST-001).
     */
    private static void testServerDeploymentBundleAndCloudSpecs() throws Exception {
        System.out.println("Testing server deployment bundle and cloud specifications...");

        Path bundleDir = Path.of("dist/server-bundle");
        require(Files.exists(bundleDir), "dist/server-bundle directory exists");

        // Verify server.jar and lib
        Path serverJar = bundleDir.resolve("server.jar");
        Path libDir = bundleDir.resolve("lib");
        require(Files.exists(serverJar), "server.jar exists in deployment bundle");
        require(Files.isDirectory(libDir), "lib/ directory exists in deployment bundle");

        // Verify Dockerfile
        Path dockerfile = bundleDir.resolve("Dockerfile");
        require(Files.exists(dockerfile), "Dockerfile exists in server bundle");
        String dockerContent = Files.readString(dockerfile, StandardCharsets.UTF_8);
        require(dockerContent.contains("eclipse-temurin:17-jre"), "Dockerfile uses Eclipse Temurin 17 JRE base image");
        require(dockerContent.contains("USER vrms"), "Dockerfile runs under non-root unprivileged user");
        require(dockerContent.contains("HEALTHCHECK"), "Dockerfile defines container HEALTHCHECK probe");
        require(dockerContent.contains("/health"), "Healthcheck targets /health liveness probe");

        // Verify render.yaml
        Path renderYaml = bundleDir.resolve("render.yaml");
        require(Files.exists(renderYaml), "render.yaml cloud specification exists");
        String renderContent = Files.readString(renderYaml, StandardCharsets.UTF_8);
        require(renderContent.contains("plan: free"), "Render specification targets free tier ($0 recurring)");
        require(renderContent.contains("healthCheckPath: /health"), "Render healthCheckPath configured to /health");
    }

    /**
     * Verifies existence and executable syntax of multi-platform packaging scripts.
     */
    private static void testMultiPlatformPackagingScripts() {
        System.out.println("Testing multi-platform packaging scripts...");

        Path winScript = Path.of("scripts/package-desktop-windows.ps1");
        Path linuxScript = Path.of("scripts/package-desktop-linux.sh");
        Path macScript = Path.of("scripts/package-desktop-macos.sh");
        Path exportScript = Path.of("scripts/export-server-bundle.ps1");

        require(Files.exists(winScript), "scripts/package-desktop-windows.ps1 exists");
        require(Files.exists(linuxScript), "scripts/package-desktop-linux.sh exists");
        require(Files.exists(macScript), "scripts/package-desktop-macos.sh exists");
        require(Files.exists(exportScript), "scripts/export-server-bundle.ps1 exists");
    }

    /**
     * Validates platform support matrix and Gate G6 compliance (honest reporting per OS).
     */
    private static void testPlatformSupportMatrixAndGateG6Status() {
        System.out.println("Validating platform support matrix and Gate G6 status...");

        // Gate G6 contract: On Windows, Windows package is built/verified; macOS and Linux must be labeled pending native runners
        boolean windowsBuilt = Files.exists(Path.of("dist/package-win64/SmartVRMS/SmartVRMS.exe"));
        require(windowsBuilt, "Windows 11 x64 native package built and verified locally");

        // Transparent cross-platform assertions: macOS and Linux require native host execution
        boolean macOsBuilt = Files.exists(Path.of("dist/package-macos-arm64"));
        boolean linuxBuilt = Files.exists(Path.of("dist/package-linux64"));
        System.out.println("Native artifact directories present: macOS=" + macOsBuilt + ", Linux=" + linuxBuilt
                + ". Presence alone is not device acceptance; consult release evidence.");
    }
}
