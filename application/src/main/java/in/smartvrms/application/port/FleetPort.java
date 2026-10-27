package in.smartvrms.application.port;

import in.smartvrms.contracts.AssetRestrictionRecord;
import in.smartvrms.contracts.StaffSession;
import in.smartvrms.contracts.VehicleDocumentRecord;
import in.smartvrms.contracts.VehicleRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetPort {
    default List<in.smartvrms.contracts.VehicleCategoryRecord> listCategories(StaffSession session) {
        throw new UnsupportedOperationException("Fleet categories are not configured.");
    }
    default in.smartvrms.contracts.VehicleCategoryRecord createCategory(StaffSession session, String code, String name) {
        throw new UnsupportedOperationException("Fleet category management is not configured.");
    }
    VehicleRecord registerVehicle(StaffSession session, UUID categoryId, String plate, String manufacturer, String model, int capacity, long dailyRatePaise, long hourlyRatePaise, UUID photoFileId);
    List<VehicleRecord> listVehicles(StaffSession session, String categoryCode, String status);
    Optional<VehicleRecord> getVehicle(StaffSession session, UUID vehicleId);
    AssetRestrictionRecord freezeAsset(StaffSession session, String assetType, UUID assetId, String reason, Instant startTime, Instant endTime);
    void resolveAssetRestriction(StaffSession session, UUID restrictionId);
    default List<AssetRestrictionRecord> listAssetRestrictions(StaffSession session, String assetType, UUID assetId) {
        return List.of();
    }
    VehicleDocumentRecord addVehicleDocument(StaffSession session, UUID vehicleId, String docType, UUID fileId, LocalDate issueDate, LocalDate expiryDate);
    List<VehicleDocumentRecord> getVehicleDocuments(StaffSession session, UUID vehicleId);
}
