package dk.madpakke.service;

import dk.madpakke.repository.SettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Holds the business's depot/start address, geocoding it once and caching the
 * coordinates in the settings table so every route starts and ends there.
 */
@Service
public class SettingsService {

    private static final String KEY_DEPOT_ADDRESS = "depot_address";
    private static final String KEY_DEPOT_LAT = "depot_lat";
    private static final String KEY_DEPOT_LON = "depot_lon";

    private final SettingsRepository settingsRepository;
    private final GeocodingService geocodingService;

    public SettingsService(SettingsRepository settingsRepository,
                            GeocodingService geocodingService,
                            @Value("${madpakke.default-depot-address:}") String defaultDepotAddress) {
        this.settingsRepository = settingsRepository;
        this.geocodingService = geocodingService;
        if (settingsRepository.get(KEY_DEPOT_ADDRESS).isEmpty() && !defaultDepotAddress.isBlank()) {
            setDepotAddress(defaultDepotAddress);
        }
    }

    public String getDepotAddress() {
        return settingsRepository.get(KEY_DEPOT_ADDRESS).orElse("");
    }

    public void setDepotAddress(String address) {
        setDepotAddress(address, null, null);
    }

    /** If lat/lon are supplied (the address was picked from the autocomplete dropdown), skip re-geocoding. */
    public void setDepotAddress(String address, Double lat, Double lon) {
        settingsRepository.set(KEY_DEPOT_ADDRESS, address);
        if (lat != null && lon != null) {
            settingsRepository.set(KEY_DEPOT_LAT, String.valueOf(lat));
            settingsRepository.set(KEY_DEPOT_LON, String.valueOf(lon));
            return;
        }
        geocodingService.geocode(address).ifPresent(result -> {
            settingsRepository.set(KEY_DEPOT_LAT, String.valueOf(result.lat()));
            settingsRepository.set(KEY_DEPOT_LON, String.valueOf(result.lon()));
        });
    }

    public GeocodeResult getDepotCoordinates() {
        String lat = settingsRepository.get(KEY_DEPOT_LAT).orElse(null);
        String lon = settingsRepository.get(KEY_DEPOT_LON).orElse(null);
        if (lat == null || lon == null) {
            throw new IllegalStateException(
                "Firmaets startadresse er ikke sat endnu. Gå til Indstillinger og indtast en adresse.");
        }
        return new GeocodeResult(Double.parseDouble(lat), Double.parseDouble(lon));
    }

    public boolean hasDepotCoordinates() {
        return settingsRepository.get(KEY_DEPOT_LAT).isPresent() && settingsRepository.get(KEY_DEPOT_LON).isPresent();
    }
}
