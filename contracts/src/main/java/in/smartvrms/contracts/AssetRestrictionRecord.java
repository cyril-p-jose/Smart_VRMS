package in.smartvrms.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AssetRestrictionRecord(
    UUID id,
    UUID companyId,
    String assetType,
    UUID assetId,
    String restrictionType,
    Instant startTime,
    Instant endTime,
    String reason,
    boolean active
) {
    public AssetRestrictionRecord {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(assetType, "assetType");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(restrictionType, "restrictionType");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(reason, "reason");
    }
}
