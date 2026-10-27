package in.smartvrms.contracts;
import java.util.UUID;
public record VehicleCategoryRecord(UUID id, String code, String name) {
    @Override public String toString() { return name + " (" + code + ")"; }
}
