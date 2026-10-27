package in.smartvrms.contracts;

import java.util.Objects;
import java.util.UUID;

public record VehicleRecord(
    UUID id,
    UUID companyId,
    UUID categoryId,
    String categoryName,
    String registrationPlate,
    String manufacturer,
    String model,
    int seatingCapacity,
    long dailyRatePaise,
    long hourlyRatePaise,
    String operationalStatus,
    UUID photoFileId,
    long version
) {
    public VehicleRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(categoryName, "categoryName");
        Objects.requireNonNull(registrationPlate, "registrationPlate");
        Objects.requireNonNull(manufacturer, "manufacturer");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(operationalStatus, "operationalStatus");
    }
}
