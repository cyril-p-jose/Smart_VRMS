import javax.net.ssl.SSLContext;
import javax.swing.SwingUtilities;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Arrays;
import in.smartvrms.desktop.DesktopMain;
import in.smartvrms.desktop.ui.Design;

/** Runs with the distribution's own java.exe and only its client JARs. */
public final class PackagedRuntimeChecks {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.home").replace('\\', '/').contains("dist/package-win64/SmartVRMS/runtime"))
            throw new AssertionError("Must use bundled runtime");
        Class.forName("com.google.gson.Gson");
        try { Class.forName("org.postgresql.Driver"); throw new AssertionError("Server driver leaked into client"); }
        catch (ClassNotFoundException expected) { }
        if (Arrays.stream(SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()).noneMatch(s -> s.contains("ECDHE")))
            throw new AssertionError("EC TLS provider missing");
        SwingUtilities.invokeAndWait(() -> { Design.install(); DesktopMain.createContent(); });
        var request = HttpRequest.newBuilder(URI.create("https://github.com/cyril-p-jose/Smart_VRMS/releases/latest"))
                .timeout(Duration.ofSeconds(12)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER)
                .build().send(request, HttpResponse.BodyHandlers.discarding());
        if (!java.util.Set.of(200, 301, 302, 404).contains(response.statusCode())) throw new AssertionError("Unexpected public GitHub status");
        System.out.println("PASS: bundled runtime, client JSON library, server exclusion, EC TLS, Swing construction and live GitHub TLS HEAD (status " + response.statusCode() + ").");
    }
}
