package in.smartvrms.server.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.*;
import in.smartvrms.application.port.AuditPort;
import in.smartvrms.application.port.AutomationPort;
import in.smartvrms.application.port.BookingPort;
import in.smartvrms.application.port.CustomerPort;
import in.smartvrms.application.port.DriverPort;
import in.smartvrms.application.port.FinancePort;
import in.smartvrms.application.port.FleetPort;
import in.smartvrms.application.port.IdempotencyPort;
import in.smartvrms.application.port.OperationsPort;
import in.smartvrms.application.port.PersonnelPort;
import in.smartvrms.application.port.ReportingPort;
import in.smartvrms.application.port.ReviewPort;
import in.smartvrms.application.port.StaffProvisioningPort;
import in.smartvrms.application.port.StoragePort;
import in.smartvrms.contracts.*;
import in.smartvrms.server.integration.AuthFailure;
import in.smartvrms.server.integration.BoundedHttp;
import in.smartvrms.server.integration.SupabaseAuth;
import in.smartvrms.server.security.Capability;
import in.smartvrms.server.security.JdbcIdempotencyService;
import in.smartvrms.server.security.StaffAuthorizer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Minimal JDK HTTP API server with strict bounds, correlation tracking, and sanitized error mapping. */
public final class VrmsHttpServer {
    private static final Logger LOG = Logger.getLogger(VrmsHttpServer.class.getName());
    private static final int MAX_BODY_BYTES = BoundedHttp.MAX_BYTES;

    @FunctionalInterface
    public interface Authorizer {
        StaffAuthorizer.Staff authorize(String token, Capability capability);
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final SupabaseAuth auth;
    private final Authorizer authorizer;
    private final boolean tls;

    private final StoragePort storage;
    private final AuditPort audit;
    private final IdempotencyPort idempotency;
    private final StaffProvisioningPort provisioning;
    private final PersonnelPort personnel;
    private final FleetPort fleet;
    private final DriverPort driver;
    private final CustomerPort customer;
    private final BookingPort booking;
    private final OperationsPort operations;
    private final FinancePort finance;
    private final AutomationPort automation;
    private final ReviewPort review;
    private final ReportingPort reporting;

    public VrmsHttpServer(HttpServerConfig config, SupabaseAuth auth, StaffAuthorizer authorizer) throws IOException {
        this(config, auth, Objects.requireNonNull(authorizer, "authorizer")::authorize, null, null, null, null, null, null, null, null);
    }

    public VrmsHttpServer(HttpServerConfig config, SupabaseAuth auth, Authorizer authorizer) throws IOException {
        this(config, auth, authorizer, null, null, null, null, null, null, null, null);
    }

    public VrmsHttpServer(
            HttpServerConfig config,
            SupabaseAuth auth,
            Authorizer authorizer,
            StoragePort storage,
            AuditPort audit,
            IdempotencyPort idempotency,
            StaffProvisioningPort provisioning) throws IOException {
        this(config, auth, authorizer, storage, audit, idempotency, provisioning, null, null, null, null);
    }

    public VrmsHttpServer(
            HttpServerConfig config,
            SupabaseAuth auth,
            Authorizer authorizer,
            StoragePort storage,
            AuditPort audit,
            IdempotencyPort idempotency,
            StaffProvisioningPort provisioning,
            PersonnelPort personnel,
            FleetPort fleet,
            DriverPort driver,
            CustomerPort customer) throws IOException {
        this(config, auth, authorizer, storage, audit, idempotency, provisioning, personnel, fleet, driver, customer, null);
    }

    public VrmsHttpServer(
            HttpServerConfig config,
            SupabaseAuth auth,
            Authorizer authorizer,
            StoragePort storage,
            AuditPort audit,
            IdempotencyPort idempotency,
            StaffProvisioningPort provisioning,
            PersonnelPort personnel,
            FleetPort fleet,
            DriverPort driver,
            CustomerPort customer,
            BookingPort booking) throws IOException {
        this(config, auth, authorizer, storage, audit, idempotency, provisioning, personnel, fleet, driver, customer, booking, null);
    }

    public VrmsHttpServer(
            HttpServerConfig config,
            SupabaseAuth auth,
            Authorizer authorizer,
            StoragePort storage,
            AuditPort audit,
            IdempotencyPort idempotency,
            StaffProvisioningPort provisioning,
            PersonnelPort personnel,
            FleetPort fleet,
            DriverPort driver,
            CustomerPort customer,
            BookingPort booking,
            OperationsPort operations) throws IOException {
        this(config, auth, authorizer, storage, audit, idempotency, provisioning, personnel, fleet, driver, customer, booking, operations, null);
    }

    public VrmsHttpServer(
            HttpServerConfig config,
            SupabaseAuth auth,
            Authorizer authorizer,
            StoragePort storage,
            AuditPort audit,
            IdempotencyPort idempotency,
            StaffProvisioningPort provisioning,
            PersonnelPort personnel,
            FleetPort fleet,
            DriverPort driver,
            CustomerPort customer,
            BookingPort booking,
            OperationsPort operations,
            FinancePort finance) throws IOException {
        this(config, auth, authorizer, storage, audit, idempotency, provisioning, personnel, fleet, driver, customer, booking, operations, finance, null, null, null);
    }

    public VrmsHttpServer(
            HttpServerConfig config,
            SupabaseAuth auth,
            Authorizer authorizer,
            StoragePort storage,
            AuditPort audit,
            IdempotencyPort idempotency,
            StaffProvisioningPort provisioning,
            PersonnelPort personnel,
            FleetPort fleet,
            DriverPort driver,
            CustomerPort customer,
            BookingPort booking,
            OperationsPort operations,
            FinancePort finance,
            AutomationPort automation,
            ReviewPort review,
            ReportingPort reporting) throws IOException {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.tls = config.isTls();
        this.storage = storage;
        this.audit = audit;
        this.idempotency = idempotency;
        this.provisioning = provisioning;
        this.personnel = personnel;
        this.fleet = fleet;
        this.driver = driver;
        this.customer = customer;
        this.booking = booking;
        this.operations = operations;
        this.finance = finance;
        this.automation = automation;
        this.review = review;
        this.reporting = reporting;

        if (config.isTls()) {
            HttpsServer httpsServer = HttpsServer.create(config.bindAddress(), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(config.sslContext()));
            this.server = httpsServer;
        } else {
            this.server = HttpServer.create(config.bindAddress(), 0);
        }

        this.executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "vrms-http-worker");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(executor);

        // Stage 9 Public Health Probes (Container liveness & monitoring)
        this.server.createContext("/health", this::handlePublicHealthCheck);
        this.server.createContext("/api/v1/health", this::handlePublicHealthCheck);

        // Stage 2A routes
        this.server.createContext("/api/v1/auth/login", exchange -> handleAuthRoute(exchange, this::handleLogin));
        this.server.createContext("/api/v1/auth/refresh", exchange -> handleAuthRoute(exchange, this::handleRefresh));
        this.server.createContext("/api/v1/auth/logout", exchange -> handleAuthRoute(exchange, this::handleLogout));
        this.server.createContext("/api/v1/auth/me", exchange -> handleAuthRoute(exchange, this::handleMe));

        // Stage 2B Member Management routes (Admin only)
        this.server.createContext("/api/v1/admin/members/invite", exchange -> handleAuthRoute(exchange, this::handleMemberInvite));
        this.server.createContext("/api/v1/admin/members/revoke", exchange -> handleAuthRoute(exchange, this::handleMemberRevoke));
        this.server.createContext("/api/v1/admin/members/handover", exchange -> handleAuthRoute(exchange, this::handleAdminHandover));
        this.server.createContext("/api/v1/admin/members", exchange -> handleAuthRoute(exchange, this::handleListMembers));

        // Stage 2B File Storage routes
        this.server.createContext("/api/v1/files/upload/initiate", exchange -> handleAuthRoute(exchange, this::handleFileInitiate));
        this.server.createContext("/api/v1/files/upload/finalize", exchange -> handleAuthRoute(exchange, this::handleFileFinalize));
        this.server.createContext("/api/v1/files/download", exchange -> handleAuthRoute(exchange, this::handleFileDownload));

        // Stage 2B Audit routes
        this.server.createContext("/api/v1/audit/events", exchange -> handleAuthRoute(exchange, this::handleAuditEvents));

        // Stage 3 Personnel routes
        this.server.createContext("/api/v1/personnel/employees", exchange -> handleAuthRoute(exchange, this::handleEmployeeRoute));
        this.server.createContext("/api/v1/personnel/employees/deactivate", exchange -> handleAuthRoute(exchange, this::handleEmployeeDeactivate));
        this.server.createContext("/api/v1/personnel/duplicates", exchange -> handleAuthRoute(exchange, this::handlePersonnelDuplicates));
        this.server.createContext("/api/v1/personnel/attendance", exchange -> handleAuthRoute(exchange, this::handleAttendanceRoute));
        this.server.createContext("/api/v1/personnel/compensation", exchange -> handleAuthRoute(exchange, this::handleCompensationRoute));

        // Stage 3 Fleet routes
        this.server.createContext("/api/v1/fleet/vehicles", exchange -> handleAuthRoute(exchange, this::handleVehicleRoute));
        this.server.createContext("/api/v1/fleet/categories", exchange -> handleAuthRoute(exchange, this::handleFleetCategories));
        this.server.createContext("/api/v1/fleet/restrictions/freeze", exchange -> handleAuthRoute(exchange, this::handleRestrictionFreeze));
        this.server.createContext("/api/v1/fleet/restrictions/resolve", exchange -> handleAuthRoute(exchange, this::handleRestrictionResolve));
        this.server.createContext("/api/v1/fleet/restrictions", exchange -> handleAuthRoute(exchange, this::handleRestrictionRoute));
        this.server.createContext("/api/v1/fleet/documents", exchange -> handleAuthRoute(exchange, this::handleDocumentRoute));

        // Stage 3 Driver routes
        this.server.createContext("/api/v1/drivers", exchange -> handleAuthRoute(exchange, this::handleDriverRoute));
        this.server.createContext("/api/v1/drivers/status", exchange -> handleAuthRoute(exchange, this::handleDriverStatus));

        // Stage 3 Customer routes
        this.server.createContext("/api/v1/customers", exchange -> handleAuthRoute(exchange, this::handleCustomerRoute));
        this.server.createContext("/api/v1/customers/search", exchange -> handleAuthRoute(exchange, this::handleCustomerSearch));
        this.server.createContext("/api/v1/customers/duplicate", exchange -> handleAuthRoute(exchange, this::handleCustomerDuplicate));
        this.server.createContext("/api/v1/customers/visits", exchange -> handleAuthRoute(exchange, this::handleCustomerVisitRoute));

        // Stage 4 Booking routes
        this.server.createContext("/api/v1/bookings/quote", exchange -> handleAuthRoute(exchange, this::handleBookingQuote));
        this.server.createContext("/api/v1/bookings/availability", exchange -> handleAuthRoute(exchange, this::handleVehicleAvailability));
        this.server.createContext("/api/v1/bookings/driver-availability", exchange -> handleAuthRoute(exchange, this::handleDriverAvailability));
        this.server.createContext("/api/v1/bookings/confirm", exchange -> handleAuthRoute(exchange, this::handleBookingConfirm));
        this.server.createContext("/api/v1/bookings/rate-plans", exchange -> handleAuthRoute(exchange, this::handleRatePlan));
        this.server.createContext("/api/v1/bookings", exchange -> handleAuthRoute(exchange, this::handleListBookings));

        // Stage 5 Operations routes
        this.server.createContext("/api/v1/operations/checkout", exchange -> handleAuthRoute(exchange, this::handleCheckout));
        this.server.createContext("/api/v1/operations/return", exchange -> handleAuthRoute(exchange, this::handleReturnInspection));
        this.server.createContext("/api/v1/operations/extensions/quote", exchange -> handleAuthRoute(exchange, this::handleExtensionQuote));
        this.server.createContext("/api/v1/operations/extensions/confirm", exchange -> handleAuthRoute(exchange, this::handleExtensionConfirm));
        this.server.createContext("/api/v1/operations/running-services", exchange -> handleAuthRoute(exchange, this::handleRunningServices));
        this.server.createContext("/api/v1/operations/running-services/detail", exchange -> handleAuthRoute(exchange, this::handleRunningServiceDetail));
        this.server.createContext("/api/v1/operations/maintenance/open", exchange -> handleAuthRoute(exchange, this::handleMaintenanceOpen));
        this.server.createContext("/api/v1/operations/maintenance/resolve", exchange -> handleAuthRoute(exchange, this::handleMaintenanceResolve));
        this.server.createContext("/api/v1/operations/maintenance/cases", exchange -> handleAuthRoute(exchange, this::handleMaintenanceCases));

        // Stage 6 Finance routes
        this.server.createContext("/api/v1/finance/requests/qr", exchange -> handleAuthRoute(exchange, this::handlePaymentQr));
        this.server.createContext("/api/v1/finance/requests", exchange -> handleAuthRoute(exchange, this::handlePaymentRequestRoute));
        this.server.createContext("/api/v1/finance/payments", exchange -> handleAuthRoute(exchange, this::handlePaymentRoute));
        this.server.createContext("/api/v1/finance/deposits", exchange -> handleAuthRoute(exchange, this::handleDepositRoute));
        this.server.createContext("/api/v1/finance/corrections", exchange -> handleAuthRoute(exchange, this::handleCorrectionRoute));
        this.server.createContext("/api/v1/finance/documents/pdf", exchange -> handleAuthRoute(exchange, this::handleDocumentPdfRoute));
        this.server.createContext("/api/v1/finance/documents", exchange -> handleAuthRoute(exchange, this::handleBillingDocumentRoute));
        this.server.createContext("/api/v1/finance/statements", exchange -> handleAuthRoute(exchange, this::handleStatementRoute));
        this.server.createContext("/api/v1/finance/ledger", exchange -> handleAuthRoute(exchange, this::handleLedgerRoute));

        // Stage 7 Automation routes
        this.server.createContext("/api/v1/automation/outbox", exchange -> handleAuthRoute(exchange, this::handleOutboxList));
        this.server.createContext("/api/v1/automation/outbox/claim", exchange -> handleAuthRoute(exchange, this::handleOutboxClaim));
        this.server.createContext("/api/v1/automation/outbox/complete", exchange -> handleAuthRoute(exchange, this::handleOutboxComplete));
        this.server.createContext("/api/v1/automation/outbox/fail", exchange -> handleAuthRoute(exchange, this::handleOutboxFail));
        this.server.createContext("/api/v1/automation/reminders/scan", exchange -> handleAuthRoute(exchange, this::handleReminderScan));
        this.server.createContext("/api/v1/automation/health", exchange -> handleAuthRoute(exchange, this::handleAutomationHealth));
        this.server.createContext("/api/v1/automation/attempts", exchange -> handleAuthRoute(exchange, this::handleAutomationAttempts));

        // Stage 7 Reviews routes
        this.server.createContext("/api/v1/reviews/correct", exchange -> handleAuthRoute(exchange, this::handleReviewCorrect));
        this.server.createContext("/api/v1/reviews", exchange -> handleAuthRoute(exchange, this::handleReviewRoute));

        // Stage 7 Reporting routes
        this.server.createContext("/api/v1/reports/operational", exchange -> handleAuthRoute(exchange, this::handleOperationalReport));
        this.server.createContext("/api/v1/reports/financial", exchange -> handleAuthRoute(exchange, this::handleFinancialReport));
        this.server.createContext("/api/v1/reports/export", exchange -> handleAuthRoute(exchange, this::handleReportExport));
    }

    public void start() {
        server.start();
        LOG.info("VrmsHttpServer started on " + baseUri());
    }

    public void stop(int delaySeconds) {
        server.stop(Math.max(0, delaySeconds));
        executor.shutdown();
        LOG.info("VrmsHttpServer stopped.");
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI baseUri() {
        String scheme = tls ? "https" : "http";
        InetSocketAddress addr = server.getAddress();
        return URI.create(scheme + "://127.0.0.1:" + addr.getPort());
    }

    @FunctionalInterface
    private interface RouteHandler {
        void handle(HttpExchange exchange, String correlationId) throws Exception;
    }

    private void handleAuthRoute(HttpExchange exchange, RouteHandler handler) {
        String correlationId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("X-Correlation-ID", correlationId);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        try {
            handler.handle(exchange, correlationId);
        } catch (AuthFailure ex) {
            int status = switch (ex.kind()) {
                case INVALID_CREDENTIALS, INVALID_SESSION -> 401;
                case RATE_LIMITED -> 429;
                case UNAVAILABLE -> 503;
                case INVALID_RESPONSE -> 502;
            };
            sendError(exchange, status, ex.getMessage(), correlationId);
        } catch (SecurityException ex) {
            sendError(exchange, 403, "Access denied.", correlationId);
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, "Invalid request.", correlationId);
        } catch (IllegalStateException ex) {
            sendError(exchange, 409, "Operation unavailable or conflicts with current state.", correlationId);
        } catch (Exception ex) {
            LOG.warning("Unhandled server error (correlation: " + correlationId + ", type: " + ex.getClass().getSimpleName() + ")");
            sendError(exchange, 500, "Internal Server Error", correlationId);
        } finally {
            exchange.close();
        }
    }

    private void handleLogin(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }

        JsonObject req = parseJsonBody(exchange);
        String email = requireStringField(req, "email");
        String password = requireStringField(req, "password");

        SupabaseAuth.AuthTokens tokens = auth.signInWithPassword(email, password);
        StaffAuthorizer.Staff staff = authorizer.authorize(tokens.accessToken(), Capability.VIEW_FLEET);

        JsonObject response = buildSessionResponse(tokens, staff);
        sendJsonResponse(exchange, 200, response);
    }

    private void handleRefresh(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }

        JsonObject req = parseJsonBody(exchange);
        String refreshToken = requireStringField(req, "refreshToken");

        SupabaseAuth.AuthTokens tokens = auth.refreshToken(refreshToken);
        StaffAuthorizer.Staff staff = authorizer.authorize(tokens.accessToken(), Capability.VIEW_FLEET);

        JsonObject response = buildSessionResponse(tokens, staff);
        sendJsonResponse(exchange, 200, response);
    }

    private void handleLogout(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token != null && !token.isBlank()) {
            auth.logout(token);
        }

        JsonObject response = new JsonObject();
        response.addProperty("status", "logged_out");
        sendJsonResponse(exchange, 200, response);
    }

    private void handleMe(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }

        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_FLEET);

        JsonObject user = new JsonObject();
        user.addProperty("id", staff.userId().toString());
        user.addProperty("companyId", staff.companyId().toString());
        user.addProperty("role", staff.role());
        user.add("capabilities", buildCapabilitiesArray(staff.role()));

        JsonObject response = new JsonObject();
        response.add("user", user);
        sendJsonResponse(exchange, 200, response);
    }

    // =========================================================================
    // Stage 2B: Member Management Handlers
    // =========================================================================

    private void handleMemberInvite(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (provisioning == null) {
            sendError(exchange, 503, "Member provisioning not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
        StaffSession session = toStaffSession(staff);

        JsonObject req = parseJsonBody(exchange);
        String email = requireStringField(req, "email");
        String fullName = req.has("fullName") ? req.get("fullName").getAsString() : "";

        // Check idempotency if key present
        String idemKey = exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
        if (idemKey != null && idempotency != null) {
            String hash = JdbcIdempotencyService.computeRequestHash(req.toString());
            var existing = idempotency.check(session, idemKey, "INVITE_EMPLOYEE", hash);
            if (existing.isPresent()) {
                IdempotencyRecord rec = existing.get();
                if ("COMPLETED".equals(rec.status()) && rec.responseBody() != null) {
                    byte[] b = rec.responseBody().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(rec.responseStatus() != null ? rec.responseStatus() : 200, b.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(b); }
                    return;
                }
            }
            idempotency.recordPending(session, idemKey, "INVITE_EMPLOYEE", hash, 300);
        }

        MemberManagementRecord record;
        try {
            record = provisioning.inviteEmployee(session, email, fullName);
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("unavailable")) {
                sendError(exchange, 503, ex.getMessage(), correlationId);
                return;
            }
            throw ex;
        }

        JsonObject res = new JsonObject();
        res.addProperty("authUserId", record.authUserId().toString());
        res.addProperty("companyId", record.companyId().toString());
        res.addProperty("role", record.role());
        res.addProperty("active", record.active());
        res.addProperty("createdAt", record.createdAt().toString());

        if (idemKey != null && idempotency != null) {
            idempotency.recordCompletion(session, idemKey, 201, res.toString());
        }

        sendJsonResponse(exchange, 201, res);
    }

    private void handleMemberRevoke(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (provisioning == null) {
            sendError(exchange, 503, "Member provisioning not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
        StaffSession session = toStaffSession(staff);

        JsonObject req = parseJsonBody(exchange);
        UUID targetId = UUID.fromString(requireStringField(req, "userId"));
        String reason = req.has("reason") ? req.get("reason").getAsString() : "Administrative action";

        MemberManagementRecord record = provisioning.revokeMembership(session, targetId, reason);

        JsonObject res = new JsonObject();
        res.addProperty("authUserId", record.authUserId().toString());
        res.addProperty("active", record.active());
        res.addProperty("role", record.role());
        res.addProperty("status", "REVOKED");
        sendJsonResponse(exchange, 200, res);
    }

    private void handleAdminHandover(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (provisioning == null) {
            sendError(exchange, 503, "Member provisioning not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
        StaffSession session = toStaffSession(staff);

        JsonObject req = parseJsonBody(exchange);
        UUID newAdminId = UUID.fromString(requireStringField(req, "newPrimaryAdminUserId"));

        provisioning.handoverPrimaryAdministrator(session, newAdminId);

        JsonObject res = new JsonObject();
        res.addProperty("status", "ADMIN_TRANSFERRED");
        res.addProperty("newPrimaryAdminUserId", newAdminId.toString());
        sendJsonResponse(exchange, 200, res);
    }

    private void handleListMembers(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (provisioning == null) {
            sendError(exchange, 503, "Member provisioning not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
        StaffSession session = toStaffSession(staff);

        List<MemberManagementRecord> members = provisioning.listMembers(session);
        JsonArray array = new JsonArray();
        for (var m : members) {
            JsonObject o = new JsonObject();
            o.addProperty("authUserId", m.authUserId().toString());
            o.addProperty("companyId", m.companyId().toString());
            o.addProperty("role", m.role());
            o.addProperty("active", m.active());
            o.addProperty("createdAt", m.createdAt().toString());
            array.add(o);
        }

        JsonObject res = new JsonObject();
        res.add("members", array);
        sendJsonResponse(exchange, 200, res);
    }

    // =========================================================================
    // Stage 2B: File Storage Handlers
    // =========================================================================

    private void handleFileInitiate(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (storage == null) {
            sendError(exchange, 503, "Storage not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_FLEET);
        StaffSession session = toStaffSession(staff);

        JsonObject req = parseJsonBody(exchange);
        String fileName = requireStringField(req, "fileName");
        String contentType = requireStringField(req, "contentType");
        long sizeBytes = req.has("sizeBytes") ? req.get("sizeBytes").getAsLong() : 0;
        FileCategory category = FileCategory.valueOf(requireStringField(req, "category"));
        FileAccessTier accessTier = FileAccessTier.valueOf(requireStringField(req, "accessTier"));

        FileMetadataRecord meta = storage.initiateUpload(session, fileName, contentType, sizeBytes, category, accessTier);

        JsonObject res = new JsonObject();
        res.addProperty("id", meta.id().toString());
        res.addProperty("storageBucket", meta.storageBucket());
        res.addProperty("storagePath", meta.storagePath());
        res.addProperty("status", meta.status());
        res.addProperty("category", meta.category().name());
        res.addProperty("accessTier", meta.accessTier().name());
        sendJsonResponse(exchange, 201, res);
    }

    private void handleFileFinalize(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (storage == null) {
            sendError(exchange, 503, "Storage not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_FLEET);
        StaffSession session = toStaffSession(staff);

        JsonObject req = parseJsonBody(exchange);
        UUID fileId = UUID.fromString(requireStringField(req, "fileId"));

        FileMetadataRecord meta = storage.finalizeUpload(session, fileId);

        JsonObject res = new JsonObject();
        res.addProperty("id", meta.id().toString());
        res.addProperty("status", meta.status());
        res.addProperty("finalizedAt", meta.finalizedAt() != null ? meta.finalizedAt().toString() : "");
        sendJsonResponse(exchange, 200, res);
    }

    private void handleFileDownload(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (storage == null) {
            sendError(exchange, 503, "Storage not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_FLEET);
        StaffSession session = toStaffSession(staff);

        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!query.containsKey("fileId")) {
            sendError(exchange, 400, "Missing required query parameter: fileId", correlationId);
            return;
        }
        UUID fileId = UUID.fromString(query.get("fileId"));
        int expiresIn = query.containsKey("expiresIn") ? Integer.parseInt(query.get("expiresIn")) : 300;

        try {
            String downloadUrl = storage.generateSignedDownloadUrl(session, fileId, expiresIn);
            JsonObject res = new JsonObject();
            res.addProperty("downloadUrl", downloadUrl);
            res.addProperty("expiresInSeconds", expiresIn);
            sendJsonResponse(exchange, 200, res);
        } catch (IllegalStateException ise) {
            sendError(exchange, 503, ise.getMessage(), correlationId);
        }
    }

    // =========================================================================
    // Stage 2B: Audit Events Handler
    // =========================================================================

    private void handleAuditEvents(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (audit == null) {
            sendError(exchange, 503, "Audit service not configured.", correlationId);
            return;
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
        StaffSession session = toStaffSession(staff);

        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String resourceType = query.get("resourceType");
        String resourceId = query.get("resourceId");
        int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 50;

        List<AuditEventRecord> events = audit.queryEvents(session, resourceType, resourceId, limit);
        JsonArray array = new JsonArray();
        for (var ev : events) {
            JsonObject o = new JsonObject();
            o.addProperty("id", ev.id().toString());
            o.addProperty("companyId", ev.companyId().toString());
            o.addProperty("actorUserId", ev.actorUserId().toString());
            o.addProperty("action", ev.action());
            o.addProperty("resourceType", ev.resourceType());
            o.addProperty("resourceId", ev.resourceId());
            o.addProperty("details", ev.detailsJson());
            o.addProperty("occurredAt", ev.occurredAt().toString());
            array.add(o);
        }

        JsonObject res = new JsonObject();
        res.add("events", array);
        sendJsonResponse(exchange, 200, res);
    }

    // =========================================================================
    // Stage 3: Personnel Handlers
    // =========================================================================

    private void handleEmployeeRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (personnel == null) {
            sendError(exchange, 503, "Personnel service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);
            String fullName = requireStringField(req, "fullName");
            String contact = requireStringField(req, "contact");
            String email = requireStringField(req, "email");
            String address = req.has("address") ? req.get("address").getAsString() : "";
            LocalDate joiningDate = req.has("joiningDate") ? LocalDate.parse(req.get("joiningDate").getAsString()) : LocalDate.now();
            boolean isDriver = req.has("isDriver") && req.get("isDriver").getAsBoolean();
            UUID photoFileId = req.has("photoFileId") && !req.get("photoFileId").isJsonNull() ? UUID.fromString(req.get("photoFileId").getAsString()) : null;

            EmployeeRecord record = personnel.registerEmployee(session, fullName, contact, email, address, joiningDate, isDriver, photoFileId);
            sendJsonResponse(exchange, 201, employeeToJson(record));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_HISTORY);
            StaffSession session = toStaffSession(staff);
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            boolean activeOnly = !Boolean.parseBoolean(query.get("includeDeactivated"));
            List<EmployeeRecord> list = personnel.listEmployees(session, activeOnly);
            JsonArray arr = new JsonArray();
            for (var e : list) arr.add(employeeToJson(e));
            JsonObject res = new JsonObject();
            res.add("employees", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    private void handleEmployeeDeactivate(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (personnel == null) {
            sendError(exchange, 503, "Personnel service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);
        UUID employeeId = UUID.fromString(requireStringField(req, "employeeId"));
        String reason = requireStringField(req, "reason");
        EmployeeRecord deactivated = personnel.deactivateEmployee(session, employeeId, reason);
        sendJsonResponse(exchange, 200, employeeToJson(deactivated));
    }

    private void handlePersonnelDuplicates(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (personnel == null) {
            sendError(exchange, 503, "Personnel service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_PERSONNEL);
        StaffSession session = toStaffSession(staff);
        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String contact = query.getOrDefault("contact", "");
        String email = query.getOrDefault("email", "");
        Optional<EmployeeRecord> dup = personnel.findDuplicateEmployee(session, contact, email);
        JsonObject res = new JsonObject();
        if (dup.isPresent()) {
            EmployeeRecord emp = dup.get();
            res.addProperty("duplicateFound", true);
            res.add("employee", employeeToJson(emp));
        } else {
            res.addProperty("duplicateFound", false);
        }
        sendJsonResponse(exchange, 200, res);
    }

    private void handleAttendanceRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (personnel == null) {
            sendError(exchange, 503, "Personnel service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_ATTENDANCE);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);
            UUID employeeId = UUID.fromString(requireStringField(req, "employeeId"));
            LocalDate date = LocalDate.parse(requireStringField(req, "date"));
            String status = requireStringField(req, "status");
            String notes = req.has("notes") ? req.get("notes").getAsString() : "";
            AttendanceRecord rec = personnel.recordAttendance(session, employeeId, date, status, notes);
            sendJsonResponse(exchange, 201, attendanceToJson(rec));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_HISTORY);
            StaffSession session = toStaffSession(staff);
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            UUID employeeId = UUID.fromString(query.get("employeeId"));
            LocalDate from = LocalDate.parse(query.getOrDefault("from", LocalDate.now().minusMonths(1).toString()));
            LocalDate to = LocalDate.parse(query.getOrDefault("to", LocalDate.now().toString()));
            List<AttendanceRecord> list = personnel.getAttendanceHistory(session, employeeId, from, to);
            JsonArray arr = new JsonArray();
            for (var a : list) arr.add(attendanceToJson(a));
            JsonObject res = new JsonObject();
            res.add("attendance", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    private void handleCompensationRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (personnel == null) {
            sendError(exchange, 503, "Personnel service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_COMPENSATION);
        if (!"ADMINISTRATOR".equalsIgnoreCase(staff.role())) {
            sendError(exchange, 403, "Compensation records restricted to Administrators.", correlationId);
            return;
        }
        StaffSession session = toStaffSession(staff);
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            JsonObject req = parseJsonBody(exchange);
            UUID employeeId = UUID.fromString(requireStringField(req, "employeeId"));
            String arrangementType = requireStringField(req, "arrangementType");
            long baseRatePaise = req.has("baseRatePaise") ? req.get("baseRatePaise").getAsLong() : 0L;
            long tripAllowancePaise = req.has("tripAllowancePaise") ? req.get("tripAllowancePaise").getAsLong() : 0L;
            LocalDate effectiveFrom = req.has("effectiveFrom") ? LocalDate.parse(req.get("effectiveFrom").getAsString()) : LocalDate.now();
            CompensationRecord rec = personnel.recordCompensation(session, employeeId, arrangementType, baseRatePaise, tripAllowancePaise, effectiveFrom);
            sendJsonResponse(exchange, 201, compensationToJson(rec));
        } else if ("GET".equals(method)) {
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            UUID employeeId = UUID.fromString(query.get("employeeId"));
            Optional<CompensationRecord> rec = personnel.getCompensation(session, employeeId);
            JsonObject res = new JsonObject();
            if (rec.isPresent()) {
                res.addProperty("found", true);
                res.add("compensation", compensationToJson(rec.get()));
            } else {
                res.addProperty("found", false);
            }
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    // =========================================================================
    // Stage 3: Fleet Handlers
    // =========================================================================

    private void handleFleetCategories(HttpExchange exchange, String correlationId) throws Exception {
        if(fleet==null){sendError(exchange,503,"Fleet service not configured.",correlationId);return;}
        String token=extractBearerToken(exchange);
        if(token==null){sendError(exchange,401,"Missing Bearer token.",correlationId);return;}
        String method=exchange.getRequestMethod();
        if("GET".equals(method)) {
            StaffSession session=toStaffSession(authorizer.authorize(token,Capability.VIEW_FLEET));
            JsonArray rows=new JsonArray();
            for(var item:fleet.listCategories(session)) {
                JsonObject row=new JsonObject();row.addProperty("id",item.id().toString());row.addProperty("code",item.code());row.addProperty("name",item.name());rows.add(row);
            }
            JsonObject root=new JsonObject();root.add("categories",rows);sendJsonResponse(exchange,200,root);
        } else if("POST".equals(method)) {
            StaffSession session=toStaffSession(authorizer.authorize(token,Capability.MANAGE_VEHICLE_MASTER));
            JsonObject body=parseJsonBody(exchange);
            var item=fleet.createCategory(session,requireStringField(body,"code"),requireStringField(body,"name"));
            JsonObject row=new JsonObject();row.addProperty("id",item.id().toString());row.addProperty("code",item.code());row.addProperty("name",item.name());sendJsonResponse(exchange,201,row);
        } else sendError(exchange,405,"Method not allowed.",correlationId);
    }

    private void handleVehicleRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (fleet == null) {
            sendError(exchange, 503, "Fleet service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_VEHICLE_MASTER);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);
            UUID categoryId = req.has("categoryId") && !req.get("categoryId").isJsonNull() ? UUID.fromString(req.get("categoryId").getAsString()) : null;
            String plate = requireStringField(req, "plate");
            String make = requireStringField(req, "make");
            String model = requireStringField(req, "model");
            int capacity = req.has("capacity") ? req.get("capacity").getAsInt() : 5;
            long dailyRatePaise = req.has("dailyRatePaise") ? req.get("dailyRatePaise").getAsLong() : 0L;
            long hourlyRatePaise = req.has("hourlyRatePaise") ? req.get("hourlyRatePaise").getAsLong() : 0L;
            UUID photoFileId = req.has("photoFileId") && !req.get("photoFileId").isJsonNull() ? UUID.fromString(req.get("photoFileId").getAsString()) : null;

            VehicleRecord rec = fleet.registerVehicle(session, categoryId, plate, make, model, capacity, dailyRatePaise, hourlyRatePaise, photoFileId);
            sendJsonResponse(exchange, 201, vehicleToJson(rec));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_FLEET);
            StaffSession session = toStaffSession(staff);
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            String categoryCode = query.get("categoryCode");
            String status = query.get("status");
            List<VehicleRecord> list = fleet.listVehicles(session, categoryCode, status);
            JsonArray arr = new JsonArray();
            for (var v : list) arr.add(vehicleToJson(v));
            JsonObject res = new JsonObject();
            res.add("vehicles", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    private void handleRestrictionFreeze(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (fleet == null) {
            sendError(exchange, 503, "Fleet service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.RECORD_FLEET_FREEZE);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);
        String assetType = requireStringField(req, "assetType");
        UUID assetId = UUID.fromString(requireStringField(req, "assetId"));
        String reason = requireStringField(req, "reason");
        Instant startTime = req.has("startTime") ? Instant.parse(req.get("startTime").getAsString()) : Instant.now();
        Instant endTime = req.has("endTime") && !req.get("endTime").isJsonNull() ? Instant.parse(req.get("endTime").getAsString()) : null;

        AssetRestrictionRecord rec = fleet.freezeAsset(session, assetType, assetId, reason, startTime, endTime);
        sendJsonResponse(exchange, 201, restrictionToJson(rec));
    }

    private void handleRestrictionResolve(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (fleet == null) {
            sendError(exchange, 503, "Fleet service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.APPROVE_MAINTENANCE_RESOLUTION);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);
        UUID restrictionId = UUID.fromString(requireStringField(req, "restrictionId"));
        fleet.resolveAssetRestriction(session, restrictionId);
        JsonObject res = new JsonObject();
        res.addProperty("status", "resolved");
        sendJsonResponse(exchange, 200, res);
    }

    private void handleRestrictionRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (fleet == null) {
            sendError(exchange, 503, "Fleet service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_FLEET);
        StaffSession session = toStaffSession(staff);

        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String assetType = query.get("assetType");
        String assetIdStr = query.get("assetId");
        if (assetType == null || assetIdStr == null) {
            sendError(exchange, 400, "Missing required query parameters: assetType and assetId", correlationId);
            return;
        }
        UUID assetId = UUID.fromString(assetIdStr);
        List<AssetRestrictionRecord> list = fleet.listAssetRestrictions(session, assetType, assetId);
        JsonArray arr = new JsonArray();
        for (var r : list) arr.add(restrictionToJson(r));
        JsonObject res = new JsonObject();
        res.add("restrictions", arr);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleDocumentRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (fleet == null) {
            sendError(exchange, 503, "Fleet service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.RENEW_ASSIGNED_DOCUMENT);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);
            UUID vehicleId = UUID.fromString(requireStringField(req, "vehicleId"));
            String docType = requireStringField(req, "docType");
            UUID fileId = UUID.fromString(requireStringField(req, "fileId"));
            LocalDate issueDate = req.has("issueDate") && !req.get("issueDate").isJsonNull() ? LocalDate.parse(req.get("issueDate").getAsString()) : null;
            LocalDate expiryDate = req.has("expiryDate") && !req.get("expiryDate").isJsonNull() ? LocalDate.parse(req.get("expiryDate").getAsString()) : null;
            VehicleDocumentRecord rec = fleet.addVehicleDocument(session, vehicleId, docType, fileId, issueDate, expiryDate);
            sendJsonResponse(exchange, 201, documentToJson(rec));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_FLEET);
            StaffSession session = toStaffSession(staff);
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            UUID vehicleId = UUID.fromString(query.get("vehicleId"));
            List<VehicleDocumentRecord> docs = fleet.getVehicleDocuments(session, vehicleId);
            JsonArray arr = new JsonArray();
            for (var d : docs) arr.add(documentToJson(d));
            JsonObject res = new JsonObject();
            res.add("documents", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    // =========================================================================
    // Stage 3: Driver Handlers
    // =========================================================================

    private void handleDriverRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (driver == null) {
            sendError(exchange, 503, "Driver service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_DRIVER_MASTER);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);
            UUID employeeId = UUID.fromString(requireStringField(req, "employeeId"));
            String licenceNumber = requireStringField(req, "licenceNumber");
            List<String> classes = new ArrayList<>();
            if (req.has("classes") && req.get("classes").isJsonArray()) {
                for (var el : req.get("classes").getAsJsonArray()) classes.add(el.getAsString());
            }
            LocalDate expiry = LocalDate.parse(requireStringField(req, "expiry"));
            int experienceYears = req.has("experienceYears") ? req.get("experienceYears").getAsInt() : 0;
            DriverProfileRecord rec = driver.registerDriverProfile(session, employeeId, licenceNumber, classes, expiry, experienceYears);
            sendJsonResponse(exchange, 201, driverToJson(rec));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_DRIVER_OPERATIONS);
            StaffSession session = toStaffSession(staff);
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            String dutyStatus = query.get("dutyStatus");
            List<DriverProfileRecord> list = driver.listDrivers(session, dutyStatus);
            JsonArray arr = new JsonArray();
            for (var d : list) arr.add(driverToJson(d));
            JsonObject res = new JsonObject();
            res.add("drivers", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    private void handleDriverStatus(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (driver == null) {
            sendError(exchange, 503, "Driver service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.REQUEST_DRIVER_UNAVAILABILITY);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);
        UUID driverId = UUID.fromString(requireStringField(req, "driverId"));
        String dutyStatus = requireStringField(req, "dutyStatus");
        driver.updateDutyStatus(session, driverId, dutyStatus);
        JsonObject res = new JsonObject();
        res.addProperty("status", "updated");
        sendJsonResponse(exchange, 200, res);
    }

    // =========================================================================
    // Stage 3: Customer Handlers
    // =========================================================================

    private void handleCustomerRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (customer == null) {
            sendError(exchange, 503, "Customer service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.BOOK_STANDARD_RENTAL);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        // FR-CUST-002: Customer creation occurs inside booking, not an independent Add Customer screen
        String bookingContext = req.has("bookingContext") && !req.get("bookingContext").isJsonNull()
                ? req.get("bookingContext").getAsString().strip() : "";
        if (bookingContext.isEmpty()) {
            sendError(exchange, 400, "Customer creation must occur within an active booking workflow (FR-CUST-002).", correlationId);
            return;
        }

        int ageAtVisit = req.has("ageAtVisit") ? req.get("ageAtVisit").getAsInt() : 0;
        if (ageAtVisit < 18 || ageAtVisit > 120) {
            sendError(exchange, 400, "Valid age-at-visit (18-120) is required for rental verification.", correlationId);
            return;
        }

        String fullName = requireStringField(req, "fullName");
        String mobile = requireStringField(req, "mobile");
        String email = requireStringField(req, "email");
        String address = req.has("address") ? req.get("address").getAsString() : "";

        CustomerRecord rec = customer.createCustomerWithVisit(session, fullName, mobile, email, address, ageAtVisit, bookingContext);
        sendJsonResponse(exchange, 201, customerToJson(rec));
    }

    private void handleCustomerSearch(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (customer == null) {
            sendError(exchange, 503, "Customer service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.UPDATE_CUSTOMER_OPERATIONS);
        StaffSession session = toStaffSession(staff);
        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String q = query.getOrDefault("query", "");
        List<CustomerRecord> list = customer.searchCustomers(session, q);
        JsonArray arr = new JsonArray();
        for (var c : list) arr.add(customerToJson(c));
        JsonObject res = new JsonObject();
        res.add("customers", arr);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleCustomerDuplicate(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (customer == null) {
            sendError(exchange, 503, "Customer service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.BOOK_STANDARD_RENTAL);
        StaffSession session = toStaffSession(staff);
        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String mobile = query.get("mobile");
        String email = query.get("email");
        Optional<CustomerRecord> dup = customer.findDuplicateCustomer(session, mobile, email);
        JsonObject res = new JsonObject();
        if (dup.isPresent()) {
            res.addProperty("duplicateFound", true);
            res.add("customer", customerToJson(dup.get()));
        } else {
            res.addProperty("duplicateFound", false);
        }
        sendJsonResponse(exchange, 200, res);
    }

    private void handleCustomerVisitRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (customer == null) {
            sendError(exchange, 503, "Customer service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.UPDATE_CUSTOMER_OPERATIONS);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);
            UUID customerId = UUID.fromString(requireStringField(req, "customerId"));
            int ageAtVisit = req.has("ageAtVisit") ? req.get("ageAtVisit").getAsInt() : 0;
            String bookingContext = requireStringField(req, "bookingContext");
            CustomerVisitRecord rec = customer.recordRepeatVisit(session, customerId, ageAtVisit, bookingContext);
            sendJsonResponse(exchange, 201, visitToJson(rec));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.UPDATE_CUSTOMER_OPERATIONS);
            StaffSession session = toStaffSession(staff);
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            UUID customerId = UUID.fromString(query.get("customerId"));
            List<CustomerVisitRecord> history = customer.getVisitHistory(session, customerId);
            JsonArray arr = new JsonArray();
            for (var v : history) arr.add(visitToJson(v));
            JsonObject res = new JsonObject();
            res.add("visits", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    private static JsonObject employeeToJson(EmployeeRecord emp) {
        JsonObject o = new JsonObject();
        o.addProperty("id", emp.id().toString());
        o.addProperty("companyId", emp.companyId().toString());
        if (emp.authUserId() != null) o.addProperty("authUserId", emp.authUserId().toString());
        o.addProperty("fullName", emp.fullName());
        o.addProperty("contactNumber", emp.contactNumber());
        o.addProperty("email", emp.email());
        o.addProperty("address", emp.address());
        o.addProperty("joiningDate", emp.joiningDate() != null ? emp.joiningDate().toString() : "");
        o.addProperty("employmentStatus", emp.employmentStatus());
        o.addProperty("isDriver", emp.isDriver());
        if (emp.photoFileId() != null) o.addProperty("photoFileId", emp.photoFileId().toString());
        o.addProperty("version", emp.version());
        return o;
    }

    private static JsonObject attendanceToJson(AttendanceRecord att) {
        JsonObject o = new JsonObject();
        o.addProperty("id", att.id().toString());
        o.addProperty("companyId", att.companyId().toString());
        o.addProperty("employeeId", att.employeeId().toString());
        o.addProperty("recordDate", att.recordDate().toString());
        o.addProperty("status", att.status());
        o.addProperty("notes", att.notes());
        if (att.recordedBy() != null) o.addProperty("recordedBy", att.recordedBy().toString());
        return o;
    }

    private static JsonObject compensationToJson(CompensationRecord comp) {
        JsonObject o = new JsonObject();
        o.addProperty("id", comp.id().toString());
        o.addProperty("companyId", comp.companyId().toString());
        o.addProperty("employeeId", comp.employeeId().toString());
        o.addProperty("arrangementType", comp.arrangementType());
        o.addProperty("baseRatePaise", comp.baseRatePaise());
        o.addProperty("tripAllowancePaise", comp.tripAllowancePaise());
        o.addProperty("effectiveFrom", comp.effectiveFrom().toString());
        return o;
    }

    private static JsonObject vehicleToJson(VehicleRecord veh) {
        JsonObject o = new JsonObject();
        o.addProperty("id", veh.id().toString());
        o.addProperty("companyId", veh.companyId().toString());
        o.addProperty("categoryId", veh.categoryId().toString());
        o.addProperty("categoryName", veh.categoryName());
        o.addProperty("registrationPlate", veh.registrationPlate());
        o.addProperty("manufacturer", veh.manufacturer());
        o.addProperty("model", veh.model());
        o.addProperty("seatingCapacity", veh.seatingCapacity());
        o.addProperty("dailyRatePaise", veh.dailyRatePaise());
        o.addProperty("hourlyRatePaise", veh.hourlyRatePaise());
        o.addProperty("operationalStatus", veh.operationalStatus());
        if (veh.photoFileId() != null) o.addProperty("photoFileId", veh.photoFileId().toString());
        o.addProperty("version", veh.version());
        return o;
    }

    private static JsonObject restrictionToJson(AssetRestrictionRecord res) {
        JsonObject o = new JsonObject();
        o.addProperty("id", res.id().toString());
        o.addProperty("companyId", res.companyId().toString());
        o.addProperty("assetType", res.assetType());
        o.addProperty("assetId", res.assetId().toString());
        o.addProperty("restrictionType", res.restrictionType());
        o.addProperty("startTime", res.startTime().toString());
        if (res.endTime() != null) o.addProperty("endTime", res.endTime().toString());
        o.addProperty("reason", res.reason());
        o.addProperty("active", res.active());
        return o;
    }

    private static JsonObject documentToJson(VehicleDocumentRecord doc) {
        JsonObject o = new JsonObject();
        o.addProperty("id", doc.id().toString());
        o.addProperty("companyId", doc.companyId().toString());
        o.addProperty("vehicleId", doc.vehicleId().toString());
        o.addProperty("docType", doc.docType());
        o.addProperty("fileId", doc.fileId().toString());
        if (doc.issueDate() != null) o.addProperty("issueDate", doc.issueDate().toString());
        if (doc.expiryDate() != null) o.addProperty("expiryDate", doc.expiryDate().toString());
        o.addProperty("version", doc.version());
        return o;
    }

    private static JsonObject driverToJson(DriverProfileRecord drv) {
        JsonObject o = new JsonObject();
        o.addProperty("id", drv.id().toString());
        o.addProperty("companyId", drv.companyId().toString());
        o.addProperty("employeeId", drv.employeeId().toString());
        o.addProperty("fullName", drv.fullName());
        o.addProperty("contactNumber", drv.contactNumber());
        o.addProperty("licenceNumber", drv.licenceNumber());
        JsonArray cls = new JsonArray();
        for (String c : drv.licenceClasses()) cls.add(c);
        o.add("licenceClasses", cls);
        o.addProperty("licenceExpiry", drv.licenceExpiry().toString());
        o.addProperty("experienceYears", drv.experienceYears());
        o.addProperty("dutyStatus", drv.dutyStatus());
        o.addProperty("isEmployed", drv.isEmployed());
        return o;
    }

    private static JsonObject customerToJson(CustomerRecord cust) {
        JsonObject o = new JsonObject();
        o.addProperty("id", cust.id().toString());
        o.addProperty("companyId", cust.companyId().toString());
        o.addProperty("fullName", cust.fullName());
        o.addProperty("mobileNumber", cust.mobileNumber());
        o.addProperty("email", cust.email());
        o.addProperty("address", cust.address());
        if (cust.createdAt() != null) o.addProperty("createdAt", cust.createdAt().toString());
        o.addProperty("version", cust.version());
        return o;
    }

    private static JsonObject visitToJson(CustomerVisitRecord visit) {
        JsonObject o = new JsonObject();
        o.addProperty("id", visit.id().toString());
        o.addProperty("companyId", visit.companyId().toString());
        o.addProperty("customerId", visit.customerId().toString());
        o.addProperty("ageAtVisit", visit.ageAtVisit());
        o.addProperty("visitDate", visit.visitDate().toString());
        o.addProperty("purpose", visit.purpose());
        return o;
    }

    // =========================================================================
    // Stage 4: Booking Handlers
    // =========================================================================

    private void handleBookingQuote(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (booking == null) {
            sendError(exchange, 503, "Booking service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.BOOK_STANDARD_RENTAL);
        JsonObject req = parseJsonBody(exchange);
        Instant startTime = Instant.parse(requireStringField(req, "startTime"));
        Instant endTime = Instant.parse(requireStringField(req, "endTime"));
        if (!req.has("items") || !req.get("items").isJsonArray()) {
            sendError(exchange, 400, "Items array is required.", correlationId);
            return;
        }

        List<BookingConfirmationCommand.PackageItem> items = new ArrayList<>();
        for (var el : req.getAsJsonArray("items")) {
            JsonObject itemObj = el.getAsJsonObject();
            UUID vehicleId = UUID.fromString(requireStringField(itemObj, "vehicleId"));
            boolean driverReq = itemObj.has("driverRequired") && itemObj.get("driverRequired").getAsBoolean();
            UUID driverId = itemObj.has("driverId") && !itemObj.get("driverId").isJsonNull() ? UUID.fromString(itemObj.get("driverId").getAsString()) : null;
            items.add(new BookingConfirmationCommand.PackageItem(vehicleId, driverReq, driverId));
        }

        BookingQuote quote = booking.calculateQuote(staff.companyId(), startTime, endTime, items);
        sendJsonResponse(exchange, 200, quoteToJson(quote));
    }

    private void handleVehicleAvailability(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (booking == null) {
            sendError(exchange, 503, "Booking service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.BOOK_STANDARD_RENTAL);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!q.containsKey("vehicleId") || !q.containsKey("startTime") || !q.containsKey("endTime")) {
            sendError(exchange, 400, "vehicleId, startTime, and endTime query parameters required.", correlationId);
            return;
        }

        UUID vehicleId = UUID.fromString(q.get("vehicleId"));
        Instant startTime = Instant.parse(q.get("startTime"));
        Instant endTime = Instant.parse(q.get("endTime"));

        boolean avail = booking.isVehicleAvailable(staff.companyId(), vehicleId, startTime, endTime);
        JsonObject res = new JsonObject();
        res.addProperty("vehicleId", vehicleId.toString());
        res.addProperty("available", avail);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleDriverAvailability(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (booking == null) {
            sendError(exchange, 503, "Booking service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.BOOK_STANDARD_RENTAL);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!q.containsKey("driverId") || !q.containsKey("startTime") || !q.containsKey("endTime")) {
            sendError(exchange, 400, "driverId, startTime, and endTime query parameters required.", correlationId);
            return;
        }

        UUID driverId = UUID.fromString(q.get("driverId"));
        Instant startTime = Instant.parse(q.get("startTime"));
        Instant endTime = Instant.parse(q.get("endTime"));

        boolean avail = booking.isDriverAvailable(staff.companyId(), driverId, startTime, endTime);
        JsonObject res = new JsonObject();
        res.addProperty("driverId", driverId.toString());
        res.addProperty("available", avail);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleBookingConfirm(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (booking == null) {
            sendError(exchange, 503, "Booking service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.BOOK_STANDARD_RENTAL);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        String idempotencyKey = req.has("idempotencyKey") ? req.get("idempotencyKey").getAsString() : UUID.randomUUID().toString();
        UUID existingCustomerId = req.has("existingCustomerId") && !req.get("existingCustomerId").isJsonNull() ? UUID.fromString(req.get("existingCustomerId").getAsString()) : null;
        String customerFullName = req.has("customerFullName") && !req.get("customerFullName").isJsonNull() ? req.get("customerFullName").getAsString() : null;
        String customerContact = req.has("customerContact") && !req.get("customerContact").isJsonNull() ? req.get("customerContact").getAsString() : null;
        String customerEmail = req.has("customerEmail") && !req.get("customerEmail").isJsonNull() ? req.get("customerEmail").getAsString() : null;
        String customerAddress = req.has("customerAddress") && !req.get("customerAddress").isJsonNull() ? req.get("customerAddress").getAsString() : null;
        int ageAtVisit = req.has("ageAtVisit") ? req.get("ageAtVisit").getAsInt() : 25;
        String visitReason = req.has("visitReason") && !req.get("visitReason").isJsonNull() ? req.get("visitReason").getAsString() : "Vehicle Rental";

        Instant startTime = Instant.parse(requireStringField(req, "startTime"));
        Instant endTime = Instant.parse(requireStringField(req, "endTime"));

        if (!req.has("items") || !req.get("items").isJsonArray()) {
            sendError(exchange, 400, "Items array is required.", correlationId);
            return;
        }

        List<BookingConfirmationCommand.PackageItem> items = new ArrayList<>();
        for (var el : req.getAsJsonArray("items")) {
            JsonObject itemObj = el.getAsJsonObject();
            UUID vehicleId = UUID.fromString(requireStringField(itemObj, "vehicleId"));
            boolean driverReq = itemObj.has("driverRequired") && itemObj.get("driverRequired").getAsBoolean();
            UUID driverId = itemObj.has("driverId") && !itemObj.get("driverId").isJsonNull() ? UUID.fromString(itemObj.get("driverId").getAsString()) : null;
            items.add(new BookingConfirmationCommand.PackageItem(vehicleId, driverReq, driverId));
        }

        long agreedAdvancePaise = req.has("agreedAdvancePaise") ? req.get("agreedAdvancePaise").getAsLong() : 0L;
        long depositPaise = req.has("depositPaise") ? req.get("depositPaise").getAsLong() : 0L;

        BookingConfirmationCommand cmd = new BookingConfirmationCommand(
                idempotencyKey,
                existingCustomerId,
                customerFullName,
                customerContact,
                customerEmail,
                customerAddress,
                ageAtVisit,
                visitReason,
                startTime,
                endTime,
                items,
                agreedAdvancePaise,
                depositPaise
        );

        BookingRecord record = booking.confirmBooking(session, cmd);
        sendJsonResponse(exchange, 201, bookingToJson(record));
    }

    private void handleRatePlan(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (booking == null) {
            sendError(exchange, 503, "Booking service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_RATES);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID vehicleId = req.has("vehicleId") && !req.get("vehicleId").isJsonNull() ? UUID.fromString(req.get("vehicleId").getAsString()) : null;
        UUID categoryId = req.has("categoryId") && !req.get("categoryId").isJsonNull() ? UUID.fromString(req.get("categoryId").getAsString()) : null;
        long dailyRatePaise = req.has("dailyRatePaise") ? req.get("dailyRatePaise").getAsLong() : 240000L;
        long hourlyRatePaise = req.has("hourlyRatePaise") ? req.get("hourlyRatePaise").getAsLong() : dailyRatePaise / 24;
        long driverDailyFeePaise = req.has("driverDailyFeePaise") ? req.get("driverDailyFeePaise").getAsLong() : 80000L;
        long driverHourlyFeePaise = req.has("driverHourlyFeePaise") ? req.get("driverHourlyFeePaise").getAsLong() : driverDailyFeePaise / 24;

        RatePlanRecord plan = new RatePlanRecord(
                null,
                session.companyId(),
                vehicleId,
                categoryId,
                dailyRatePaise,
                hourlyRatePaise,
                driverDailyFeePaise,
                driverHourlyFeePaise,
                Instant.now(),
                null,
                1L
        );

        RatePlanRecord configured = booking.configureRatePlan(session, plan);
        JsonObject res = new JsonObject();
        res.addProperty("id", configured.id().toString());
        res.addProperty("dailyRatePaise", configured.dailyRatePaise());
        res.addProperty("hourlyRatePaise", configured.hourlyRatePaise());
        res.addProperty("driverDailyFeePaise", configured.driverDailyFeePaise());
        sendJsonResponse(exchange, 201, res);
    }

    private void handleListBookings(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (booking == null) {
            sendError(exchange, 503, "Booking service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_HISTORY);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        int limit = q.containsKey("limit") ? Integer.parseInt(q.get("limit")) : 50;
        int offset = q.containsKey("offset") ? Integer.parseInt(q.get("offset")) : 0;

        List<BookingRecord> list = booking.listBookings(staff.companyId(), limit, offset);
        JsonArray arr = new JsonArray();
        for (var b : list) {
            arr.add(bookingToJson(b));
        }
        JsonObject res = new JsonObject();
        res.add("bookings", arr);
        sendJsonResponse(exchange, 200, res);
    }

    private static JsonObject quoteToJson(BookingQuote q) {
        JsonObject o = new JsonObject();
        o.addProperty("startTime", q.startTime().toString());
        o.addProperty("endTime", q.endTime().toString());
        o.addProperty("durationHours", q.durationHours());
        o.addProperty("billableDays", q.billableDays());
        o.addProperty("billableRemainderHours", q.billableRemainderHours());
        o.addProperty("baseTotalPaise", q.baseTotalPaise());
        o.addProperty("driverTotalPaise", q.driverTotalPaise());
        o.addProperty("taxPaise", q.taxPaise());
        o.addProperty("grandTotalPaise", q.grandTotalPaise());
        o.addProperty("suggestedAdvancePaise", q.suggestedAdvancePaise());
        o.addProperty("depositPaise", q.depositPaise());

        JsonArray arr = new JsonArray();
        for (AssignmentQuote aq : q.assignments()) {
            JsonObject item = new JsonObject();
            item.addProperty("vehicleId", aq.vehicleId().toString());
            item.addProperty("vehicleName", aq.vehicleName());
            item.addProperty("category", aq.category());
            item.addProperty("registrationPlate", aq.registrationPlate());
            if (aq.driverId() != null) item.addProperty("driverId", aq.driverId().toString());
            if (aq.driverName() != null) item.addProperty("driverName", aq.driverName());
            item.addProperty("driverRequired", aq.driverRequired());
            item.addProperty("dailyRatePaise", aq.dailyRatePaise());
            item.addProperty("hourlyRatePaise", aq.hourlyRatePaise());
            item.addProperty("vehicleTotalPaise", aq.vehicleTotalPaise());
            item.addProperty("driverTotalPaise", aq.driverTotalPaise());
            item.addProperty("lineTotalPaise", aq.lineTotalPaise());
            arr.add(item);
        }
        o.add("assignments", arr);
        return o;
    }

    private static JsonObject bookingToJson(BookingRecord b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.id().toString());
        o.addProperty("companyId", b.companyId().toString());
        o.addProperty("customerId", b.customerId().toString());
        o.addProperty("visitId", b.visitId().toString());
        o.addProperty("bookingNumber", b.bookingNumber());
        o.addProperty("status", b.status());
        o.addProperty("startTime", b.startTime().toString());
        o.addProperty("endTime", b.endTime().toString());
        o.addProperty("baseTotalPaise", b.baseTotalPaise());
        o.addProperty("driverTotalPaise", b.driverTotalPaise());
        o.addProperty("taxPaise", b.taxPaise());
        o.addProperty("totalPaise", b.totalPaise());
        o.addProperty("agreedAdvancePaise", b.agreedAdvancePaise());
        o.addProperty("actualAdvanceReceivedPaise", b.actualAdvanceReceivedPaise());
        o.addProperty("depositPaise", b.depositPaise());
        o.add("pricingSnapshot", JsonParser.parseString(b.pricingSnapshotJson()));
        o.addProperty("createdBy", b.createdBy().toString());
        o.addProperty("createdAt", b.createdAt().toString());
        o.addProperty("version", b.version());

        JsonArray arr = new JsonArray();
        for (BookingAssignmentRecord a : b.assignments()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", a.id().toString());
            item.addProperty("companyId", a.companyId().toString());
            item.addProperty("bookingId", a.bookingId().toString());
            item.addProperty("vehicleId", a.vehicleId().toString());
            if (a.driverId() != null) item.addProperty("driverId", a.driverId().toString());
            item.addProperty("driverRequired", a.driverRequired());
            item.addProperty("startTime", a.startTime().toString());
            item.addProperty("endTime", a.endTime().toString());
            item.addProperty("dailyRatePaise", a.dailyRatePaise());
            item.addProperty("hourlyRatePaise", a.hourlyRatePaise());
            item.addProperty("vehicleTotalPaise", a.vehicleTotalPaise());
            item.addProperty("driverTotalPaise", a.driverTotalPaise());
            item.addProperty("assignmentTotalPaise", a.assignmentTotalPaise());
            item.addProperty("status", a.status());
            arr.add(item);
        }
        o.add("assignments", arr);
        return o;
    }

    private static StaffSession toStaffSession(StaffAuthorizer.Staff staff) {
        Set<String> caps = new HashSet<>();
        boolean isAdmin = "ADMINISTRATOR".equalsIgnoreCase(staff.role());
        for (Capability cap : Capability.values()) {
            if (isAdmin || !cap.administratorOnly()) caps.add(cap.name());
        }
        return new StaffSession(staff.userId(), staff.companyId(), staff.role(), "", caps);
    }

    private static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isBlank()) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                map.put(pair[0].strip(), pair[1].strip());
            } else if (pair.length == 1) {
                map.put(pair[0].strip(), "");
            }
        }
        return map;
    }

    private JsonObject buildSessionResponse(SupabaseAuth.AuthTokens tokens, StaffAuthorizer.Staff staff) {
        JsonObject user = new JsonObject();
        user.addProperty("id", staff.userId().toString());
        user.addProperty("companyId", staff.companyId().toString());
        user.addProperty("role", staff.role());
        user.addProperty("email", tokens.email());
        user.add("capabilities", buildCapabilitiesArray(staff.role()));

        JsonObject response = new JsonObject();
        response.addProperty("accessToken", tokens.accessToken());
        response.addProperty("refreshToken", tokens.refreshToken());
        response.addProperty("expiresIn", tokens.expiresInSeconds());
        response.addProperty("tokenType", "bearer");
        response.add("user", user);
        return response;
    }

    private JsonArray buildCapabilitiesArray(String role) {
        JsonArray array = new JsonArray();
        boolean isAdmin = "ADMINISTRATOR".equalsIgnoreCase(role);
        for (Capability cap : Capability.values()) {
            if (isAdmin || !cap.administratorOnly()) {
                array.add(cap.name());
            }
        }
        return array;
    }

    private String extractBearerToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authHeader.substring(7).strip();
        return token.isEmpty() ? null : token;
    }

    private JsonObject parseJsonBody(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new IllegalArgumentException("Content-Type must be application/json.");
        }
        byte[] body = readBoundedBody(exchange.getRequestBody(), MAX_BODY_BYTES);
        try {
            String text = new String(body, StandardCharsets.UTF_8);
            var element = JsonParser.parseString(text);
            if (!element.isJsonObject()) throw new IllegalArgumentException("Request body must be a JSON object.");
            return element.getAsJsonObject();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed JSON request body.");
        }
    }

    private byte[] readBoundedBody(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            total += r;
            if (total > maxBytes) {
                throw new IllegalArgumentException("Request body exceeds limit of " + maxBytes + " bytes.");
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private String requireStringField(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive() || !json.get(field).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid field: " + field);
        }
        String val = json.get(field).getAsString();
        if (val.isBlank()) {
            throw new IllegalArgumentException("Field cannot be empty: " + field);
        }
        return val;
    }

    private void sendJsonResponse(HttpExchange exchange, int status, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // =========================================================================
    // Stage 5: Operations Handlers
    // =========================================================================

    private void handleCheckout(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.CHECKOUT);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID assignmentId = UUID.fromString(requireStringField(req, "assignmentId"));
        Instant actualTime = req.has("actualTime") ? Instant.parse(req.get("actualTime").getAsString()) : Instant.now();
        int odometer = req.has("odometer") ? req.get("odometer").getAsInt() : 0;
        int fuelPercent = req.has("fuelPercent") ? req.get("fuelPercent").getAsInt() : 100;
        String notes = req.has("notes") && !req.get("notes").isJsonNull() ? req.get("notes").getAsString() : null;

        CustodyRecord record = operations.recordCheckout(session, new CheckoutCommand(assignmentId, actualTime, odometer, fuelPercent, notes));
        sendJsonResponse(exchange, 200, custodyToJson(record));
    }

    private void handleReturnInspection(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.RECORD_RETURN_INSPECTION);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID assignmentId = UUID.fromString(requireStringField(req, "assignmentId"));
        Instant actualTime = req.has("actualTime") ? Instant.parse(req.get("actualTime").getAsString()) : Instant.now();
        int odometer = req.has("odometer") ? req.get("odometer").getAsInt() : 0;
        int fuelPercent = req.has("fuelPercent") ? req.get("fuelPercent").getAsInt() : 100;
        String condition = req.has("condition") ? req.get("condition").getAsString() : "ACCEPTABLE";
        String damageNotes = req.has("damageNotes") && !req.get("damageNotes").isJsonNull() ? req.get("damageNotes").getAsString() : null;
        UUID photoFileId = req.has("damagePhotoFileId") && !req.get("damagePhotoFileId").isJsonNull() ? UUID.fromString(req.get("damagePhotoFileId").getAsString()) : null;
        String issueType = req.has("issueType") && !req.get("issueType").isJsonNull() ? req.get("issueType").getAsString() : "CUSTOMER_DAMAGE";
        long estimatedCost = req.has("estimatedRepairCostPaise") ? req.get("estimatedRepairCostPaise").getAsLong() : 0L;
        long allegedRecovery = req.has("allegedCustomerRecoveryPaise") ? req.get("allegedCustomerRecoveryPaise").getAsLong() : 0L;

        CustodyRecord record = operations.recordReturnInspection(session, new ReturnInspectionCommand(
                assignmentId, actualTime, odometer, fuelPercent, condition, damageNotes, photoFileId, issueType, estimatedCost, allegedRecovery
        ));
        sendJsonResponse(exchange, 200, custodyToJson(record));
    }

    private void handleExtensionQuote(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.BOOK_STANDARD_RENTAL);
        JsonObject req = parseJsonBody(exchange);

        UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
        Instant revisedEndTime = Instant.parse(requireStringField(req, "revisedEndTime"));
        String reason = req.has("reason") ? req.get("reason").getAsString() : "Customer rental extension";

        List<UUID> assignmentIds = new ArrayList<>();
        if (req.has("assignmentIds") && req.get("assignmentIds").isJsonArray()) {
            for (var el : req.getAsJsonArray("assignmentIds")) {
                assignmentIds.add(UUID.fromString(el.getAsString()));
            }
        }

        ExtensionQuote quote = operations.quoteExtension(staff.companyId(), new ExtensionCommand(bookingId, assignmentIds, revisedEndTime, reason));
        sendJsonResponse(exchange, 200, extensionQuoteToJson(quote));
    }

    private void handleExtensionConfirm(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.EXTEND_AT_SAVED_RATES);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
        Instant revisedEndTime = Instant.parse(requireStringField(req, "revisedEndTime"));
        String reason = requireStringField(req, "reason");

        List<UUID> assignmentIds = new ArrayList<>();
        if (req.has("assignmentIds") && req.get("assignmentIds").isJsonArray()) {
            for (var el : req.getAsJsonArray("assignmentIds")) {
                assignmentIds.add(UUID.fromString(el.getAsString()));
            }
        }

        BookingRecord record = operations.extendAssignments(session, new ExtensionCommand(bookingId, assignmentIds, revisedEndTime, reason));
        sendJsonResponse(exchange, 200, bookingToJson(record));
    }

    private void handleRunningServices(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_HISTORY);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        boolean overdueOnly = "true".equalsIgnoreCase(q.get("overdueOnly"));

        List<RunningServiceSummary> list = operations.listRunningServices(staff.companyId(), overdueOnly);
        JsonArray arr = new JsonArray();
        for (var s : list) {
            arr.add(runningServiceToJson(s));
        }
        JsonObject res = new JsonObject();
        res.add("runningServices", arr);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleRunningServiceDetail(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_HISTORY);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!q.containsKey("bookingId")) {
            sendError(exchange, 400, "Missing required bookingId parameter.", correlationId);
            return;
        }
        UUID bookingId = UUID.fromString(q.get("bookingId"));
        RunningServiceSummary summary = operations.getRunningServiceDetail(staff.companyId(), bookingId);
        sendJsonResponse(exchange, 200, runningServiceToJson(summary));
    }

    private void handleMaintenanceOpen(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.RECORD_MAINTENANCE_EVIDENCE);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID vehicleId = UUID.fromString(requireStringField(req, "vehicleId"));
        UUID assignmentId = req.has("assignmentId") && !req.get("assignmentId").isJsonNull() ? UUID.fromString(req.get("assignmentId").getAsString()) : null;
        UUID custodyId = req.has("custodyId") && !req.get("custodyId").isJsonNull() ? UUID.fromString(req.get("custodyId").getAsString()) : null;
        String issueType = req.has("issueType") ? req.get("issueType").getAsString() : "ORDINARY_WEAR";
        long estimatedCost = req.has("estimatedCostPaise") ? req.get("estimatedCostPaise").getAsLong() : 0L;
        long allegedRecovery = req.has("allegedRecoveryPaise") ? req.get("allegedRecoveryPaise").getAsLong() : 0L;
        String notes = requireStringField(req, "notes");
        UUID photoFileId = req.has("photoFileId") && !req.get("photoFileId").isJsonNull() ? UUID.fromString(req.get("photoFileId").getAsString()) : null;

        MaintenanceCaseRecord record = operations.openMaintenanceCase(session, vehicleId, assignmentId, custodyId, issueType, estimatedCost, allegedRecovery, notes, photoFileId);
        sendJsonResponse(exchange, 201, maintenanceCaseToJson(record));
    }

    private void handleMaintenanceResolve(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.APPROVE_MAINTENANCE_RESOLUTION);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID caseId = UUID.fromString(requireStringField(req, "caseId"));
        long actualCost = req.has("actualCostPaise") ? req.get("actualCostPaise").getAsLong() : 0L;
        long approvedRecovery = req.has("approvedCustomerRecoveryPaise") ? req.get("approvedCustomerRecoveryPaise").getAsLong() : 0L;
        String resolutionNotes = req.has("resolutionNotes") && !req.get("resolutionNotes").isJsonNull() ? req.get("resolutionNotes").getAsString() : "Maintenance completed";

        MaintenanceCaseRecord record = operations.resolveMaintenanceCase(session, new MaintenanceResolutionCommand(caseId, actualCost, approvedRecovery, resolutionNotes));
        sendJsonResponse(exchange, 200, maintenanceCaseToJson(record));
    }

    private void handleMaintenanceCases(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (operations == null) {
            sendError(exchange, 503, "Operations service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_HISTORY);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        UUID vehicleId = q.containsKey("vehicleId") ? UUID.fromString(q.get("vehicleId")) : null;
        boolean openOnly = "true".equalsIgnoreCase(q.get("openOnly"));

        List<MaintenanceCaseRecord> list = operations.listMaintenanceCases(staff.companyId(), vehicleId, openOnly);
        JsonArray arr = new JsonArray();
        for (var c : list) {
            arr.add(maintenanceCaseToJson(c));
        }
        JsonObject res = new JsonObject();
        res.add("cases", arr);
        sendJsonResponse(exchange, 200, res);
    }

    private static JsonObject custodyToJson(CustodyRecord c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id().toString());
        o.addProperty("companyId", c.companyId().toString());
        o.addProperty("assignmentId", c.assignmentId().toString());
        o.addProperty("vehicleId", c.vehicleId().toString());
        o.addProperty("checkoutTime", c.checkoutTime().toString());
        o.addProperty("checkoutOdometer", c.checkoutOdometer());
        o.addProperty("checkoutFuelPercent", c.checkoutFuelPercent());
        if (c.checkoutNotes() != null) o.addProperty("checkoutNotes", c.checkoutNotes());
        if (c.returnTime() != null) o.addProperty("returnTime", c.returnTime().toString());
        if (c.returnOdometer() != null) o.addProperty("returnOdometer", c.returnOdometer());
        if (c.returnFuelPercent() != null) o.addProperty("returnFuelPercent", c.returnFuelPercent());
        o.addProperty("inspectionStatus", c.inspectionStatus());
        if (c.damageNotes() != null) o.addProperty("damageNotes", c.damageNotes());
        if (c.damagePhotoFileId() != null) o.addProperty("damagePhotoFileId", c.damagePhotoFileId().toString());
        o.addProperty("checkoutBy", c.checkoutBy().toString());
        if (c.returnedBy() != null) o.addProperty("returnedBy", c.returnedBy().toString());
        if (c.createdAt() != null) o.addProperty("createdAt", c.createdAt().toString());
        return o;
    }

    private static JsonObject extensionQuoteToJson(ExtensionQuote eq) {
        JsonObject o = new JsonObject();
        o.addProperty("bookingId", eq.bookingId().toString());
        o.addProperty("totalDeltaPaise", eq.totalDeltaPaise());
        o.addProperty("revisedEndTime", eq.revisedEndTime().toString());
        JsonArray arr = new JsonArray();
        for (var d : eq.assignmentDeltas()) {
            JsonObject item = new JsonObject();
            item.addProperty("assignmentId", d.assignmentId().toString());
            item.addProperty("vehicleId", d.vehicleId().toString());
            item.addProperty("previousEndTime", d.previousEndTime().toString());
            item.addProperty("revisedEndTime", d.revisedEndTime().toString());
            item.addProperty("incrementalVehiclePaise", d.incrementalVehiclePaise());
            item.addProperty("incrementalDriverPaise", d.incrementalDriverPaise());
            item.addProperty("incrementalTotalPaise", d.incrementalTotalPaise());
            arr.add(item);
        }
        o.add("assignmentDeltas", arr);
        return o;
    }

    private static JsonObject runningServiceToJson(RunningServiceSummary s) {
        JsonObject o = new JsonObject();
        o.addProperty("bookingId", s.bookingId().toString());
        o.addProperty("bookingNumber", s.bookingNumber());
        o.addProperty("customerId", s.customerId().toString());
        o.addProperty("customerName", s.customerName());
        o.addProperty("customerContact", s.customerContact());
        o.addProperty("visitId", s.visitId().toString());
        o.addProperty("bookingStatus", s.bookingStatus());
        o.addProperty("startTime", s.startTime().toString());
        o.addProperty("endTime", s.endTime().toString());
        o.addProperty("isOverdue", s.isOverdue());
        o.addProperty("vehicleCount", s.vehicleCount());

        JsonArray plates = new JsonArray();
        for (String p : s.vehiclePlates()) plates.add(p);
        o.add("vehiclePlates", plates);

        JsonArray drivers = new JsonArray();
        for (String d : s.driverNames()) drivers.add(d);
        o.add("driverNames", drivers);

        o.addProperty("totalPaise", s.totalPaise());
        o.addProperty("advanceReceivedPaise", s.advanceReceivedPaise());
        o.addProperty("balancePaise", s.balancePaise());

        JsonArray assigns = new JsonArray();
        for (var a : s.assignments()) {
            JsonObject item = new JsonObject();
            item.addProperty("assignmentId", a.assignmentId().toString());
            item.addProperty("vehicleId", a.vehicleId().toString());
            item.addProperty("registrationPlate", a.registrationPlate());
            item.addProperty("categoryName", a.categoryName());
            if (a.driverId() != null) item.addProperty("driverId", a.driverId().toString());
            if (a.driverName() != null) item.addProperty("driverName", a.driverName());
            item.addProperty("startTime", a.startTime().toString());
            item.addProperty("endTime", a.endTime().toString());
            item.addProperty("assignmentStatus", a.assignmentStatus());
            item.addProperty("custodyStatus", a.custodyStatus());
            if (a.inspectionStatus() != null) item.addProperty("inspectionStatus", a.inspectionStatus());
            assigns.add(item);
        }
        o.add("assignments", assigns);
        return o;
    }

    private static JsonObject maintenanceCaseToJson(MaintenanceCaseRecord m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id().toString());
        o.addProperty("companyId", m.companyId().toString());
        o.addProperty("vehicleId", m.vehicleId().toString());
        if (m.assignmentId() != null) o.addProperty("assignmentId", m.assignmentId().toString());
        if (m.custodyId() != null) o.addProperty("custodyId", m.custodyId().toString());
        if (m.restrictionId() != null) o.addProperty("restrictionId", m.restrictionId().toString());
        o.addProperty("issueType", m.issueType());
        o.addProperty("status", m.status());
        o.addProperty("estimatedCostPaise", m.estimatedCostPaise());
        o.addProperty("allegedCustomerRecoveryPaise", m.allegedCustomerRecoveryPaise());
        o.addProperty("actualCostPaise", m.actualCostPaise());
        o.addProperty("approvedCustomerRecoveryPaise", m.approvedCustomerRecoveryPaise());
        o.addProperty("notes", m.notes());
        if (m.damagePhotoFileId() != null) o.addProperty("damagePhotoFileId", m.damagePhotoFileId().toString());
        if (m.resolutionNotes() != null) o.addProperty("resolutionNotes", m.resolutionNotes());
        o.addProperty("reportedBy", m.reportedBy().toString());
        if (m.resolvedBy() != null) o.addProperty("resolvedBy", m.resolvedBy().toString());
        o.addProperty("createdAt", m.createdAt().toString());
        if (m.resolvedAt() != null) o.addProperty("resolvedAt", m.resolvedAt().toString());
        return o;
    }

    // =========================================================================
    // Stage 6: Finance Handlers
    // =========================================================================

    private void handlePaymentRequestRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
        long amountPaise = req.get("amountPaise").getAsLong();
        String paymentType = req.has("paymentType") ? req.get("paymentType").getAsString() : "RENTAL_BALANCE";
        Instant dueAt = req.has("dueAt") && !req.get("dueAt").isJsonNull() ? Instant.parse(req.get("dueAt").getAsString()) : null;
        String notes = req.has("notes") && !req.get("notes").isJsonNull() ? req.get("notes").getAsString() : null;

        PaymentRequestRecord rec = finance.createPaymentRequest(session, bookingId, amountPaise, paymentType, dueAt, notes);
        sendJsonResponse(exchange, 201, paymentRequestToJson(rec));
    }

    private void handlePaymentQr(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
        StaffSession session = toStaffSession(staff);

        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!q.containsKey("requestId")) {
            sendError(exchange, 400, "Missing requestId parameter", correlationId);
            return;
        }
        UUID reqId = UUID.fromString(q.get("requestId"));
        QrIntentRecord qr = finance.getPaymentQr(session, reqId);

        JsonObject res = new JsonObject();
        res.addProperty("requestId", qr.requestId().toString());
        res.addProperty("upiUri", qr.upiUri());
        res.addProperty("merchantVpa", qr.merchantVpa());
        res.addProperty("merchantName", qr.merchantName());
        res.addProperty("amountPaise", qr.amountPaise());
        res.addProperty("base64PngQr", qr.base64PngQr());
        sendJsonResponse(exchange, 200, res);
    }

    private void handlePaymentRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.RECORD_VERIFIED_PAYMENT);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);

            UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
            UUID reqId = req.has("paymentRequestId") && !req.get("paymentRequestId").isJsonNull() ? UUID.fromString(req.get("paymentRequestId").getAsString()) : null;
            long amountPaise = req.get("amountPaise").getAsLong();
            String paymentMethod = req.has("paymentMethod") ? req.get("paymentMethod").getAsString() : "CASH";
            String externalReference = req.has("externalReference") && !req.get("externalReference").isJsonNull() ? req.get("externalReference").getAsString() : null;
            String verificationEvidence = requireStringField(req, "verificationEvidence");
            String notes = req.has("notes") && !req.get("notes").isJsonNull() ? req.get("notes").getAsString() : null;
            long advAlloc = req.has("allocateToAdvancePaise") ? req.get("allocateToAdvancePaise").getAsLong() : 0L;
            long depAlloc = req.has("allocateToDepositPaise") ? req.get("allocateToDepositPaise").getAsLong() : 0L;

            RecordPaymentCommand cmd = new RecordPaymentCommand(bookingId, reqId, amountPaise, paymentMethod, externalReference, verificationEvidence, notes, advAlloc, depAlloc);
            PaymentRecord rec = finance.recordVerifiedPayment(session, cmd);
            sendJsonResponse(exchange, 201, paymentToJson(rec));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
            StaffSession session = toStaffSession(staff);
            Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
            if (!q.containsKey("bookingId")) {
                sendError(exchange, 400, "Missing bookingId parameter", correlationId);
                return;
            }
            UUID bookingId = UUID.fromString(q.get("bookingId"));
            List<PaymentRecord> list = finance.listPaymentsForBooking(session, bookingId);
            JsonArray arr = new JsonArray();
            for (var p : list) arr.add(paymentToJson(p));
            JsonObject res = new JsonObject();
            res.add("payments", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed. Use GET or POST.", correlationId);
        }
    }

    private void handleDepositRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
            StaffSession session = toStaffSession(staff);
            Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
            if (!q.containsKey("bookingId")) {
                sendError(exchange, 400, "Missing bookingId parameter", correlationId);
                return;
            }
            UUID bookingId = UUID.fromString(q.get("bookingId"));
            RefundableDepositRecord dep = finance.getDepositForBooking(session, bookingId);
            if (dep == null) {
                sendJsonResponse(exchange, 200, new JsonObject());
            } else {
                sendJsonResponse(exchange, 200, depositToJson(dep));
            }
        } else if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.POST_REFUND);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);

            UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
            String action = requireStringField(req, "action");
            long amountPaise = req.get("amountPaise").getAsLong();
            String reason = requireStringField(req, "reason");

            RefundableDepositRecord rec = finance.manageDeposit(session, bookingId, action, amountPaise, reason);
            sendJsonResponse(exchange, 200, depositToJson(rec));
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    private void handleCorrectionRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.POST_CREDIT_NOTE);
        StaffSession session = toStaffSession(staff);
        JsonObject req = parseJsonBody(exchange);

        UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
        UUID paymentId = req.has("paymentId") && !req.get("paymentId").isJsonNull() ? UUID.fromString(req.get("paymentId").getAsString()) : null;
        String correctionType = req.has("correctionType") ? req.get("correctionType").getAsString() : "CREDIT_NOTE";
        long amountPaise = req.get("amountPaise").getAsLong();
        String reason = requireStringField(req, "reason");
        String bankPayoutRef = req.has("bankPayoutRef") && !req.get("bankPayoutRef").isJsonNull() ? req.get("bankPayoutRef").getAsString() : null;

        FinancialCorrectionCommand cmd = new FinancialCorrectionCommand(bookingId, paymentId, correctionType, amountPaise, reason, bankPayoutRef);
        FinancialCorrectionRecord rec = finance.approveCorrection(session, cmd);
        sendJsonResponse(exchange, 201, correctionToJson(rec));
    }

    private void handleBillingDocumentRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.PREPARE_RENTAL_DOCUMENT);
            StaffSession session = toStaffSession(staff);
            JsonObject req = parseJsonBody(exchange);

            UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
            String documentType = req.has("documentType") ? req.get("documentType").getAsString() : "PRO_FORMA_INVOICE";
            String notes = req.has("notes") && !req.get("notes").isJsonNull() ? req.get("notes").getAsString() : null;

            BillingDocumentRecord doc = finance.issueBillingDocument(session, bookingId, documentType, notes);
            sendJsonResponse(exchange, 201, billingDocumentToJson(doc));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.READ_RENTAL_DOCUMENT);
            StaffSession session = toStaffSession(staff);
            Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
            if (!q.containsKey("bookingId")) {
                sendError(exchange, 400, "Missing bookingId parameter", correlationId);
                return;
            }
            UUID bookingId = UUID.fromString(q.get("bookingId"));
            List<BillingDocumentRecord> list = finance.listBillingDocuments(session, bookingId);
            JsonArray arr = new JsonArray();
            for (var d : list) arr.add(billingDocumentToJson(d));
            JsonObject res = new JsonObject();
            res.add("documents", arr);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed.", correlationId);
        }
    }

    private void handleDocumentPdfRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.READ_RENTAL_DOCUMENT);
        StaffSession session = toStaffSession(staff);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!q.containsKey("documentId")) {
            sendError(exchange, 400, "Missing documentId parameter", correlationId);
            return;
        }
        UUID docId = UUID.fromString(q.get("documentId"));
        byte[] pdfBytes = finance.renderDocumentPdf(session, docId);

        exchange.getResponseHeaders().set("Content-Type", "application/pdf");
        exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"document-" + docId.toString().substring(0, 8) + ".pdf\"");
        exchange.sendResponseHeaders(200, pdfBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(pdfBytes);
        }
    }

    private void handleStatementRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
        StaffSession session = toStaffSession(staff);
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!q.containsKey("customerId")) {
            sendError(exchange, 400, "Missing customerId parameter", correlationId);
            return;
        }
        UUID custId = UUID.fromString(q.get("customerId"));
        CustomerStatement statement = finance.getCustomerStatement(session, custId);

        JsonObject res = new JsonObject();
        res.addProperty("customerId", statement.customerId().toString());
        res.addProperty("customerName", statement.customerName());
        res.addProperty("totalBilledPaise", statement.totalBilledPaise());
        res.addProperty("totalPaidPaise", statement.totalPaidPaise());
        res.addProperty("balanceDuePaise", statement.balanceDuePaise());
        res.addProperty("depositHeldPaise", statement.depositHeldPaise());

        JsonArray payArr = new JsonArray();
        for (var p : statement.payments()) payArr.add(paymentToJson(p));
        res.add("payments", payArr);

        JsonArray docArr = new JsonArray();
        for (var d : statement.documents()) docArr.add(billingDocumentToJson(d));
        res.add("documents", docArr);

        sendJsonResponse(exchange, 200, res);
    }

    private void handleLedgerRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (finance == null) {
            sendError(exchange, 503, "Finance service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_COMPANY_MARGIN);
        StaffSession session = toStaffSession(staff);

        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        Instant from = q.containsKey("from") ? Instant.parse(q.get("from")) : null;
        Instant to = q.containsKey("to") ? Instant.parse(q.get("to")) : null;

        LedgerSummary summary = finance.getLedgerSummary(session, from, to);
        JsonObject res = new JsonObject();
        res.addProperty("fromTime", summary.fromTime() != null ? summary.fromTime().toString() : "");
        res.addProperty("toTime", summary.toTime() != null ? summary.toTime().toString() : "");
        res.addProperty("totalRevenueEarnedPaise", summary.totalRevenueEarnedPaise());
        res.addProperty("totalCashCollectedPaise", summary.totalCashCollectedPaise());
        res.addProperty("totalReceivablesPaise", summary.totalReceivablesPaise());
        res.addProperty("totalDepositLiabilityPaise", summary.totalDepositLiabilityPaise());
        res.addProperty("totalExpensesPaise", summary.totalExpensesPaise());
        res.addProperty("operatingMarginPaise", summary.operatingMarginPaise());
        sendJsonResponse(exchange, 200, res);
    }

    private static JsonObject paymentRequestToJson(PaymentRequestRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id().toString());
        o.addProperty("companyId", r.companyId().toString());
        o.addProperty("bookingId", r.bookingId().toString());
        o.addProperty("customerId", r.customerId().toString());
        o.addProperty("amountPaise", r.amountPaise());
        o.addProperty("currency", r.currency());
        o.addProperty("paymentType", r.paymentType());
        o.addProperty("dueAt", r.dueAt().toString());
        o.addProperty("status", r.status());
        o.addProperty("merchantVpa", r.merchantVpa());
        o.addProperty("merchantName", r.merchantName());
        o.addProperty("qrPayload", r.qrPayload());
        if (r.notes() != null) o.addProperty("notes", r.notes());
        o.addProperty("createdBy", r.createdBy().toString());
        o.addProperty("createdAt", r.createdAt().toString());
        return o;
    }

    private static JsonObject paymentToJson(PaymentRecord p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id().toString());
        o.addProperty("companyId", p.companyId().toString());
        o.addProperty("bookingId", p.bookingId().toString());
        o.addProperty("customerId", p.customerId().toString());
        if (p.paymentRequestId() != null) o.addProperty("paymentRequestId", p.paymentRequestId().toString());
        o.addProperty("amountPaise", p.amountPaise());
        o.addProperty("currency", p.currency());
        o.addProperty("paymentMethod", p.paymentMethod());
        if (p.externalReference() != null) o.addProperty("externalReference", p.externalReference());
        o.addProperty("status", p.status());
        o.addProperty("verificationEvidence", p.verificationEvidence());
        o.addProperty("verifiedBy", p.verifiedBy().toString());
        o.addProperty("verifiedAt", p.verifiedAt().toString());
        if (p.notes() != null) o.addProperty("notes", p.notes());
        o.addProperty("createdAt", p.createdAt().toString());
        return o;
    }

    private static JsonObject depositToJson(RefundableDepositRecord d) {
        JsonObject o = new JsonObject();
        o.addProperty("id", d.id().toString());
        o.addProperty("companyId", d.companyId().toString());
        o.addProperty("bookingId", d.bookingId().toString());
        o.addProperty("customerId", d.customerId().toString());
        o.addProperty("depositReceivedPaise", d.depositReceivedPaise());
        o.addProperty("depositRefundedPaise", d.depositRefundedPaise());
        o.addProperty("depositAppliedPaise", d.depositAppliedPaise());
        o.addProperty("remainingHeldPaise", d.remainingHeldPaise());
        o.addProperty("status", d.status());
        o.addProperty("heldAt", d.heldAt().toString());
        if (d.refundedAt() != null) o.addProperty("refundedAt", d.refundedAt().toString());
        if (d.refundRef() != null) o.addProperty("refundRef", d.refundRef());
        if (d.refundApprovedBy() != null) o.addProperty("refundApprovedBy", d.refundApprovedBy().toString());
        if (d.notes() != null) o.addProperty("notes", d.notes());
        return o;
    }

    private static JsonObject correctionToJson(FinancialCorrectionRecord c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id().toString());
        o.addProperty("companyId", c.companyId().toString());
        o.addProperty("bookingId", c.bookingId().toString());
        if (c.paymentId() != null) o.addProperty("paymentId", c.paymentId().toString());
        o.addProperty("correctionType", c.correctionType());
        o.addProperty("amountPaise", c.amountPaise());
        o.addProperty("reason", c.reason());
        o.addProperty("approvedBy", c.approvedBy().toString());
        if (c.bankPayoutRef() != null) o.addProperty("bankPayoutRef", c.bankPayoutRef());
        o.addProperty("status", c.status());
        o.addProperty("createdAt", c.createdAt().toString());
        return o;
    }

    private static JsonObject billingDocumentToJson(BillingDocumentRecord b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.id().toString());
        o.addProperty("companyId", b.companyId().toString());
        o.addProperty("bookingId", b.bookingId().toString());
        o.addProperty("customerId", b.customerId().toString());
        o.addProperty("documentType", b.documentType());
        o.addProperty("documentNumber", b.documentNumber());
        o.addProperty("documentVersion", b.documentVersion());
        o.addProperty("status", b.status());
        o.addProperty("totalPaise", b.totalPaise());
        o.addProperty("advancePaidPaise", b.advancePaidPaise());
        o.addProperty("balanceDuePaise", b.balanceDuePaise());
        o.addProperty("depositHeldPaise", b.depositHeldPaise());
        o.addProperty("taxPaise", b.taxPaise());
        o.addProperty("isBetaProforma", b.isBetaProforma());
        o.addProperty("snapshotJson", b.snapshotJson());
        if (b.fileMetadataId() != null) o.addProperty("fileMetadataId", b.fileMetadataId().toString());
        o.addProperty("issuedBy", b.issuedBy().toString());
        o.addProperty("issuedAt", b.issuedAt().toString());
        o.addProperty("createdAt", b.createdAt().toString());
        return o;
    }

    private void sendError(HttpExchange exchange, int status, String message, String correlationId) {
        try {
            JsonObject err = new JsonObject();
            err.addProperty("error", errorTitle(status));
            err.addProperty("message", message);
            err.addProperty("correlationId", correlationId);
            sendJsonResponse(exchange, status, err);
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    // =========================================================================
    // Stage 7: Automation Handlers (FR-MSG-001, FR-DOC-003, FR-DOC-004)
    // =========================================================================

    private void handleOutboxList(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (automation == null) {
            sendError(exchange, 503, "Automation service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        StaffSession session = toStaffSession(staff);

        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        int limit = 50;
        if (q.containsKey("limit")) {
            try { limit = Integer.parseInt(q.get("limit")); } catch (NumberFormatException ignored) {}
        }

        List<OutboxMessageRecord> list = automation.listRecentMessages(session, limit);
        JsonArray array = new JsonArray();
        for (OutboxMessageRecord m : list) {
            array.add(outboxMessageToJson(m));
        }
        JsonObject res = new JsonObject();
        res.add("messages", array);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleOutboxClaim(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (automation == null) {
            sendError(exchange, 503, "Automation service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        sendError(exchange, 503, "Email delivery is unavailable: no provider worker is configured.", correlationId);
    }

    private void handleOutboxComplete(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (automation == null) {
            sendError(exchange, 503, "Automation service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        sendError(exchange, 503, "Email delivery is unavailable: provider acceptance cannot be verified.", correlationId);
    }

    private void handleOutboxFail(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (automation == null) {
            sendError(exchange, 503, "Automation service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        sendError(exchange, 503, "Email delivery is unavailable: no provider worker is configured.", correlationId);
    }

    private void handleReminderScan(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (automation == null) {
            sendError(exchange, 503, "Automation service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        StaffSession session = toStaffSession(staff);

        int count = automation.scanDocumentReminders(session, Instant.now());
        JsonObject res = new JsonObject();
        res.addProperty("scannedCount", count);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleAutomationHealth(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (automation == null) {
            sendError(exchange, 503, "Automation service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        StaffSession session = toStaffSession(staff);

        WorkerHealthStatus status = automation.getWorkerHealth(session);
        JsonObject res = new JsonObject();
        res.addProperty("lastScanTime", status.lastScanTime() != null ? status.lastScanTime().toString() : "");
        res.addProperty("pendingMessagesCount", status.pendingMessagesCount());
        res.addProperty("claimedMessagesCount", status.claimedMessagesCount());
        res.addProperty("failedMessagesCount", status.failedMessagesCount());
        res.addProperty("acceptedMessagesCount", status.acceptedMessagesCount());
        res.addProperty("oldestPendingAgeSeconds", status.oldestPendingAgeSeconds());
        res.addProperty("isLagging", status.isLagging());
        sendJsonResponse(exchange, 200, res);
    }

    private void handleAutomationAttempts(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (automation == null) {
            sendError(exchange, 503, "Automation service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        StaffSession session = toStaffSession(staff);

        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        if (!q.containsKey("outboxId")) {
            sendError(exchange, 400, "Missing required query parameter: outboxId", correlationId);
            return;
        }
        UUID outboxId = UUID.fromString(q.get("outboxId"));

        List<DeliveryAttemptRecord> attempts = automation.listAttempts(session, outboxId);
        JsonArray array = new JsonArray();
        for (DeliveryAttemptRecord a : attempts) {
            array.add(deliveryAttemptToJson(a));
        }
        JsonObject res = new JsonObject();
        res.add("attempts", array);
        sendJsonResponse(exchange, 200, res);
    }

    // =========================================================================
    // Stage 7: Service Reviews Handlers (FR-REV-001, FR-REV-002)
    // =========================================================================

    private void handleReviewRoute(HttpExchange exchange, String correlationId) throws Exception {
        if (review == null) {
            sendError(exchange, 503, "Review service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.RECORD_REVIEW);
            StaffSession session = toStaffSession(staff);

            JsonObject req = parseJsonBody(exchange);
            UUID bookingId = UUID.fromString(requireStringField(req, "bookingId"));
            int rating = req.has("rating") ? req.get("rating").getAsInt() : 5;
            String feedbackText = requireStringField(req, "feedbackText");
            String communicationSource = req.has("communicationSource") ? req.get("communicationSource").getAsString() : "DESK_IN_PERSON";
            boolean consentGiven = !req.has("consentGiven") || req.get("consentGiven").getAsBoolean();

            ServiceReviewCommand cmd = new ServiceReviewCommand(bookingId, rating, feedbackText, communicationSource, consentGiven);
            ServiceReviewRecord rec = review.recordReview(session, cmd);
            sendJsonResponse(exchange, 201, reviewToJson(rec));
        } else if ("GET".equals(method)) {
            StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
            StaffSession session = toStaffSession(staff);

            Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
            UUID bookingId = q.containsKey("bookingId") && !q.get("bookingId").isBlank() ? UUID.fromString(q.get("bookingId")) : null;
            UUID customerId = q.containsKey("customerId") && !q.get("customerId").isBlank() ? UUID.fromString(q.get("customerId")) : null;

            List<ServiceReviewRecord> list = review.listReviews(session, bookingId, customerId);
            JsonArray array = new JsonArray();
            for (ServiceReviewRecord r : list) {
                array.add(reviewToJson(r));
            }
            JsonObject res = new JsonObject();
            res.add("reviews", array);
            sendJsonResponse(exchange, 200, res);
        } else {
            sendError(exchange, 405, "Method Not Allowed. Use GET or POST.", correlationId);
        }
    }

    private void handleReviewCorrect(HttpExchange exchange, String correlationId) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.", correlationId);
            return;
        }
        if (review == null) {
            sendError(exchange, 503, "Review service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.MANAGE_NOTIFICATIONS);
        StaffSession session = toStaffSession(staff);

        JsonObject req = parseJsonBody(exchange);
        UUID reviewId = UUID.fromString(requireStringField(req, "reviewId"));
        int revisedRating = req.has("revisedRating") ? req.get("revisedRating").getAsInt() : 5;
        String revisedFeedback = requireStringField(req, "revisedFeedback");
        String correctionReason = requireStringField(req, "correctionReason");

        ServiceReviewCorrectionCommand cmd = new ServiceReviewCorrectionCommand(reviewId, revisedRating, revisedFeedback, correctionReason);
        ServiceReviewRecord rec = review.correctReview(session, cmd);
        sendJsonResponse(exchange, 200, reviewToJson(rec));
    }

    // =========================================================================
    // Stage 7: Reporting Handlers (FR-FIN-003, FR-ACC-003, FR-FIN-005)
    // =========================================================================

    private void handleOperationalReport(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (reporting == null) {
            sendError(exchange, 503, "Reporting service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
        StaffSession session = toStaffSession(staff);

        ReportingPeriodFilter filter = parseReportingFilter(exchange);
        OperationalReportSummary rep = reporting.getOperationalReport(session, filter);

        JsonObject res = new JsonObject();
        res.addProperty("fromTime", rep.fromTime().toString());
        res.addProperty("toTime", rep.toTime().toString());
        res.addProperty("totalBookingsCount", rep.totalBookingsCount());
        res.addProperty("totalAssignmentsCount", rep.totalAssignmentsCount());
        res.addProperty("activeRentalsCount", rep.activeRentalsCount());
        res.addProperty("completedRentalsCount", rep.completedRentalsCount());
        res.addProperty("overdueRentalsCount", rep.overdueRentalsCount());
        res.addProperty("maintenanceCasesCount", rep.maintenanceCasesCount());
        res.addProperty("reviewsCount", rep.reviewsCount());
        res.addProperty("averageReviewRating", rep.averageReviewRating());
        sendJsonResponse(exchange, 200, res);
    }

    private void handleFinancialReport(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (reporting == null) {
            sendError(exchange, 503, "Reporting service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_COMPANY_MARGIN);
        StaffSession session = toStaffSession(staff);

        ReportingPeriodFilter filter = parseReportingFilter(exchange);
        FinancialReportSummary rep = reporting.getFinancialReport(session, filter);

        JsonObject res = new JsonObject();
        res.addProperty("fromTime", rep.fromTime().toString());
        res.addProperty("toTime", rep.toTime().toString());
        res.addProperty("periodLabel", rep.periodLabel());
        res.addProperty("totalRevenueBilledPaise", rep.totalRevenueBilledPaise());
        res.addProperty("totalCashCollectedPaise", rep.totalCashCollectedPaise());
        res.addProperty("outstandingReceivablesPaise", rep.outstandingReceivablesPaise());
        res.addProperty("refundableDepositLiabilityPaise", rep.refundableDepositLiabilityPaise());
        res.addProperty("maintenanceExpensesPaise", rep.maintenanceExpensesPaise());
        res.addProperty("compensationExpensesPaise", rep.compensationExpensesPaise());
        res.addProperty("totalOperatingExpensesPaise", rep.totalOperatingExpensesPaise());
        res.addProperty("netOperatingAccrualMarginPaise", rep.netOperatingAccrualMarginPaise());
        sendJsonResponse(exchange, 200, res);
    }

    private void handleReportExport(HttpExchange exchange, String correlationId) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.", correlationId);
            return;
        }
        if (reporting == null) {
            sendError(exchange, 503, "Reporting service not configured.", correlationId);
            return;
        }
        String token = extractBearerToken(exchange);
        if (token == null) {
            sendError(exchange, 401, "Missing Bearer token.", correlationId);
            return;
        }
        StaffAuthorizer.Staff staff = authorizer.authorize(token, Capability.VIEW_OPERATIONAL_BALANCE);
        StaffSession session = toStaffSession(staff);

        ReportingPeriodFilter filter = parseReportingFilter(exchange);
        String csv = reporting.exportReportCsv(session, filter);

        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"vrms_report.csv\"");
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private ReportingPeriodFilter parseReportingFilter(HttpExchange exchange) {
        Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
        Instant from = q.containsKey("from") && !q.get("from").isBlank() ? Instant.parse(q.get("from")) : Instant.now().minusSeconds(86400L * 30);
        Instant to = q.containsKey("to") && !q.get("to").isBlank() ? Instant.parse(q.get("to")) : Instant.now();
        String granularity = q.getOrDefault("granularity", "MONTH");
        UUID vehicleId = q.containsKey("vehicleId") && !q.get("vehicleId").isBlank() ? UUID.fromString(q.get("vehicleId")) : null;
        UUID customerId = q.containsKey("customerId") && !q.get("customerId").isBlank() ? UUID.fromString(q.get("customerId")) : null;
        return new ReportingPeriodFilter(from, to, granularity, vehicleId, customerId);
    }

    private static JsonObject outboxMessageToJson(OutboxMessageRecord m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id().toString());
        o.addProperty("companyId", m.companyId().toString());
        o.addProperty("eventType", m.eventType());
        o.addProperty("idempotencyKey", m.idempotencyKey());
        o.addProperty("recipientEmail", m.recipientEmail());
        o.addProperty("recipientRole", m.recipientRole());
        o.addProperty("subject", m.subject());
        o.addProperty("templateName", m.templateName());
        o.addProperty("payloadJson", m.payloadJson());
        if (m.attachmentDocId() != null) o.addProperty("attachmentDocId", m.attachmentDocId().toString());
        o.addProperty("status", m.status());
        if (m.leaseUntil() != null) o.addProperty("leaseUntil", m.leaseUntil().toString());
        if (m.claimedBy() != null) o.addProperty("claimedBy", m.claimedBy());
        o.addProperty("attemptsCount", m.attemptsCount());
        o.addProperty("maxAttempts", m.maxAttempts());
        if (m.nextAttemptAt() != null) o.addProperty("nextAttemptAt", m.nextAttemptAt().toString());
        o.addProperty("createdAt", m.createdAt().toString());
        return o;
    }

    private static JsonObject deliveryAttemptToJson(DeliveryAttemptRecord a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id().toString());
        o.addProperty("outboxId", a.outboxId().toString());
        o.addProperty("attemptNumber", a.attemptNumber());
        o.addProperty("startedAt", a.startedAt().toString());
        if (a.finishedAt() != null) o.addProperty("finishedAt", a.finishedAt().toString());
        o.addProperty("providerName", a.providerName());
        o.addProperty("providerResponseCode", a.providerResponseCode());
        if (a.providerMessageId() != null) o.addProperty("providerMessageId", a.providerMessageId());
        if (a.errorMessage() != null) o.addProperty("errorMessage", a.errorMessage());
        o.addProperty("outcome", a.outcome());
        return o;
    }

    private static JsonObject reviewToJson(ServiceReviewRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id().toString());
        o.addProperty("companyId", r.companyId().toString());
        o.addProperty("bookingId", r.bookingId().toString());
        o.addProperty("customerId", r.customerId().toString());
        if (r.visitId() != null) o.addProperty("visitId", r.visitId().toString());
        o.addProperty("rating", r.rating());
        o.addProperty("feedbackText", r.feedbackText());
        o.addProperty("communicationSource", r.communicationSource());
        o.addProperty("consentGiven", r.consentGiven());
        o.addProperty("status", r.status());
        o.addProperty("recordedBy", r.recordedBy().toString());
        o.addProperty("recordedAt", r.recordedAt().toString());
        return o;
    }

    private String errorTitle(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }

    private void handlePublicHealthCheck(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("status", "HEALTHY");
        resp.addProperty("version", "0.1.0-SNAPSHOT");
        resp.addProperty("service", "Smart VRMS API");
        resp.addProperty("timestamp", Instant.now().toString());
        byte[] bytes = resp.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
