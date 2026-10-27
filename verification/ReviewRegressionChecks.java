package in.smartvrms.verification;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.integration.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import in.smartvrms.desktop.ui.*;
import in.smartvrms.server.domain.QuoteEngine;
import javax.swing.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Regression checks for defects discovered during the independent release review. */
public final class ReviewRegressionChecks {
    private static int checks;
    private static void check(boolean result) { if (!result) throw new AssertionError("Review regression failed"); checks++; }
    private static void rejected(Runnable operation) {
        try { operation.run(); throw new AssertionError("Expected failure"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        String release = "https://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v";
        check(UpdateChecker.newerRelease("0.1.1-preview", release + "0.1.1").isPresent());
        check(UpdateChecker.newerRelease("0.1.1", release + "0.1.1").isEmpty());
        check(UpdateChecker.newerRelease("0.1.9", release + "0.1.10").isPresent());
        check(UpdateChecker.newerRelease("0.2.0", release + "0.1.99").isEmpty());
        check(UpdateChecker.newerRelease("0.1.1-preview", release + "0.1.2-preview").isPresent());
        check(UpdateChecker.newerRelease("0.1.2-preview", release + "0.1.2-preview").isEmpty());
        check(UpdateChecker.newerRelease("0.1.2-preview", release + "0.1.2").isPresent());
        for (String value : List.of("https://evil.example/releases/tag/v9.0.0", release + "1.0.0?token=x",
                release + "9999999999999999999999.0.0", "http://github.com/cyril-p-jose/Smart_VRMS/releases/tag/v2.0.0"))
            check(UpdateChecker.newerRelease("0.1.1", value).isEmpty());
        Instant pickup = Instant.parse("2026-10-25T00:00:00Z");
        var vehicle = UUID.randomUUID();
        check(QuoteEngine.calculateAssignmentQuote(vehicle, "Test", 240000, 10000, false, 0, 0,
                pickup, pickup.plusSeconds(86400)).vehicleTotalPaise() == 240000);
        check(QuoteEngine.calculateAssignmentQuote(vehicle, "Test", 240000, 10000, false, 0, 0,
                pickup, pickup.plusSeconds(86401)).vehicleTotalPaise() == 250000);
        check(QuoteEngine.calculateAssignmentQuote(vehicle, "Test", 240000, 10000, false, 0, 0,
                pickup, pickup.plusSeconds(86400).plusNanos(1)).vehicleTotalPaise() == 250000);
        rejected(() -> QuoteEngine.calculate(pickup, pickup.plusSeconds(3600),
                List.of(new QuoteEngine.AssignmentInput(vehicle, "Test", "CAR", "TEST", null, null, false, null))));
        try { QuoteEngine.calculateAssignmentQuote(vehicle, "Test", Long.MAX_VALUE, 1, false, 0, 0,
                pickup, pickup.plusSeconds(172800)); throw new AssertionError("Overflow must fail"); }
        catch (ArithmeticException expected) { checks++; }
        rejected(() -> { try { DesktopConfig.saveEndpoint(URI.create("http://evil.example")); }
            catch (java.io.IOException ex) { throw new AssertionError(ex); } });
        SwingUtilities.invokeAndWait(() -> {
            Design.install();
            var session = new StaffSession(UUID.randomUUID(), UUID.randomUUID(), "ADMINISTRATOR", "", Set.of());
            WorkspaceView view = new WorkspaceView(() -> {}, session);
            check(!view.financeView.recordPaymentBtn.isEnabled());
            check(!view.runningServices.checkoutButton.isEnabled());
            view.setSession(session);
            check(!view.financeView.recordPaymentBtn.isEnabled());
            check(!view.automationReportsView.scanRemindersBtn.isEnabled());
            view.setSession(null);
            check(view.session() == null);
        });
        desktopProtocol();
        System.out.println("PASS: " + checks + " independent review regression assertions. Fixture HTTP only; no live business acceptance claimed.");
    }
    private static void desktopProtocol() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        UUID user = UUID.randomUUID(), company = UUID.randomUUID();
        String email = "staff\"escaped@example.invalid";
        JsonObject root = new JsonObject(); root.addProperty("accessToken", "fixture.jwt.value");
        root.addProperty("refreshToken", "fixture-refresh"); root.addProperty("expiresIn", 3600);
        JsonObject staff = new JsonObject(); staff.addProperty("id", user.toString()); staff.addProperty("companyId", company.toString());
        staff.addProperty("role", "ADMINISTRATOR"); staff.addProperty("email", email); staff.add("capabilities", new JsonArray()); root.add("user", staff);
        AtomicReference<String> payload = new AtomicReference<>(root.toString());
        AtomicReference<String> sentPassword = new AtomicReference<>();
        server.createContext("/api/v1/auth/login", exchange -> {
            JsonObject input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            sentPassword.set(input.get("password").getAsString());
            byte[] body = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new HttpAuthenticationClient(DesktopConfig.fromEndpoint("http://127.0.0.1:" + server.getAddress().getPort()));
            String password = "quote\"tab\tnewline\nbackslash\\";
            check(client.signIn(email, password.toCharArray()).session().email().equals(email));
            check(password.equals(sentPassword.get()));
            payload.set(root.toString().replace("\"expiresIn\":3600", "\"expiresIn\":3600,\"expiresIn\":7200"));
            rejected(() -> client.signIn(email, password.toCharArray()));
            payload.set("{\"padding\":\"" + "x".repeat(70000) + "\"}");
            rejected(() -> client.signIn(email, password.toCharArray()));
            root.addProperty("expiresIn", -1); payload.set(root.toString());
            rejected(() -> client.signIn(email, password.toCharArray()));
        } finally { server.stop(0); }
    }
}
