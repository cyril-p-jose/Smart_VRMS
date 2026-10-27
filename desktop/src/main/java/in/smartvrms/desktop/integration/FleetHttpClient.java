package in.smartvrms.desktop.integration;

import com.google.gson.*;
import in.smartvrms.contracts.*;
import in.smartvrms.desktop.infrastructure.DesktopConfig;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Real fleet API adapter. Mutations are never automatically retried after a timeout. */
public final class FleetHttpClient {
    private final URI endpoint;
    private final DesktopSession session;
    private final DesktopHttp http=new DesktopHttp();
    public FleetHttpClient(DesktopConfig config,DesktopSession session){this.endpoint=config.apiEndpoint().orElseThrow();this.session=session;}
    public List<VehicleCategoryRecord> categories(){
        var root=send("/api/v1/fleet/categories",null);List<VehicleCategoryRecord> rows=new ArrayList<>();
        for(var element:root.getAsJsonArray("categories")){var row=element.getAsJsonObject();rows.add(new VehicleCategoryRecord(uuid(row,"id"),text(row,"code"),text(row,"name")));}return List.copyOf(rows);
    }
    public List<VehicleRecord> vehicles(){
        var root=send("/api/v1/fleet/vehicles",null);List<VehicleRecord> rows=new ArrayList<>();
        for(var item:root.getAsJsonArray("vehicles")){var row=item.getAsJsonObject();rows.add(new VehicleRecord(uuid(row,"id"),uuid(row,"companyId"),uuid(row,"categoryId"),
            text(row,"categoryName"),text(row,"registrationPlate"),text(row,"manufacturer"),text(row,"model"),
            Math.toIntExact(DesktopJson.longVal(row,"seatingCapacity")),DesktopJson.longVal(row,"dailyRatePaise"),DesktopJson.longVal(row,"hourlyRatePaise"),
            text(row,"operationalStatus"),row.has("photoFileId")?uuid(row,"photoFileId"):null,DesktopJson.longVal(row,"version")));}return List.copyOf(rows);
    }
    public void createCategory(String code,String name){JsonObject json=new JsonObject();json.addProperty("code",code);json.addProperty("name",name);send("/api/v1/fleet/categories",json);}
    public void registerVehicle(UUID category,String plate,String make,String model,int seats,long dailyPaise,long hourlyPaise){
        JsonObject json=new JsonObject();json.addProperty("categoryId",category.toString());json.addProperty("plate",plate);json.addProperty("make",make);json.addProperty("model",model);
        json.addProperty("capacity",seats);json.addProperty("dailyRatePaise",dailyPaise);json.addProperty("hourlyRatePaise",hourlyPaise);send("/api/v1/fleet/vehicles",json);
    }
    public void freezeAsset(String assetType, UUID assetId, String reason, java.time.Instant startTime, java.time.Instant endTime){
        JsonObject json = new JsonObject();
        json.addProperty("assetType", assetType);
        json.addProperty("assetId", assetId.toString());
        json.addProperty("reason", reason);
        if (startTime != null) json.addProperty("startTime", startTime.toString());
        if (endTime != null) json.addProperty("endTime", endTime.toString());
        send("/api/v1/fleet/restrictions/freeze", json);
    }
    public void resolveRestriction(UUID restrictionId){
        JsonObject json = new JsonObject();
        json.addProperty("restrictionId", restrictionId.toString());
        send("/api/v1/fleet/restrictions/resolve", json);
    }
    public List<AssetRestrictionRecord> restrictions(String assetType, UUID assetId){
        var root = send("/api/v1/fleet/restrictions?assetType=" + assetType + "&assetId=" + assetId, null);
        List<AssetRestrictionRecord> list = new ArrayList<>();
        if (root.has("restrictions")) {
            for (var elem : root.getAsJsonArray("restrictions")) {
                var row = elem.getAsJsonObject();
                list.add(new AssetRestrictionRecord(
                    uuid(row, "id"), uuid(row, "companyId"), text(row, "assetType"), uuid(row, "assetId"),
                    text(row, "restrictionType"), java.time.Instant.parse(text(row, "startTime")),
                    row.has("endTime") && !row.get("endTime").isJsonNull() ? java.time.Instant.parse(text(row, "endTime")) : null,
                    text(row, "reason"), row.has("active") && row.get("active").getAsBoolean()
                ));
            }
        }
        return List.copyOf(list);
    }
    public List<VehicleDocumentRecord> documents(UUID vehicleId){
        var root = send("/api/v1/fleet/documents?vehicleId=" + vehicleId, null);
        List<VehicleDocumentRecord> list = new ArrayList<>();
        if (root.has("documents")) {
            for (var elem : root.getAsJsonArray("documents")) {
                var row = elem.getAsJsonObject();
                list.add(new VehicleDocumentRecord(
                    uuid(row, "id"), uuid(row, "companyId"), uuid(row, "vehicleId"),
                    text(row, "docType"), uuid(row, "fileId"),
                    java.time.LocalDate.parse(text(row, "issueDate")),
                    row.has("expiryDate") && !row.get("expiryDate").isJsonNull() ? java.time.LocalDate.parse(text(row, "expiryDate")) : null,
                    row.has("version") ? row.get("version").getAsInt() : 1
                ));
            }
        }
        return List.copyOf(list);
    }
    public void addDocument(UUID vehicleId, String docType, UUID fileId, java.time.LocalDate issueDate, java.time.LocalDate expiryDate){
        JsonObject json = new JsonObject();
        json.addProperty("vehicleId", vehicleId.toString());
        json.addProperty("docType", docType);
        json.addProperty("fileId", fileId.toString());
        if (issueDate != null) json.addProperty("issueDate", issueDate.toString());
        if (expiryDate != null) json.addProperty("expiryDate", expiryDate.toString());
        send("/api/v1/fleet/documents", json);
    }
    public String downloadUrl(UUID fileId) {
        var root = send("/api/v1/files/download?fileId=" + fileId, null);
        return text(root, "downloadUrl");
    }
    private JsonObject send(String path,JsonObject payload){
        var builder=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(20)).header("Accept","application/json")
            .header("Authorization","Bearer "+session.accessToken());
        var request=payload==null?builder.GET().build():builder.header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(),StandardCharsets.UTF_8)).build();
        var response=http.send(request);
        if(response.status()==401)throw new SecurityException("Session expired. Sign in again.");
        if(response.status()==403)throw new SecurityException("Your current account does not have permission for this action.");
        if(response.status()!=200 && response.status()!=201)throw new IllegalStateException("The request did not complete. Refresh before trying again; do not assume a save succeeded.");
        if(!response.contentType().split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new IllegalStateException("Invalid API response.");
        return DesktopJson.object(response.body());
    }
    private static String text(JsonObject json,String name){return DesktopJson.string(json,name);}
    private static UUID uuid(JsonObject json,String name){return UUID.fromString(text(json,name));}
}
