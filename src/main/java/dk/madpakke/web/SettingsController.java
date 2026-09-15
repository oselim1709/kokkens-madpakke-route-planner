package dk.madpakke.web;

import dk.madpakke.service.GeocodingService;
import dk.madpakke.service.SettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final GeocodingService geocodingService;

    public SettingsController(SettingsService settingsService, GeocodingService geocodingService) {
        this.settingsService = settingsService;
        this.geocodingService = geocodingService;
    }

    @GetMapping
    public Map<String, Object> get() {
        return Map.of(
            "depotAddress", settingsService.getDepotAddress(),
            "depotGeocoded", settingsService.hasDepotCoordinates(),
            "usingGoogleGeocoding", geocodingService.usingGoogle()
        );
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        String address = (String) body.get("depotAddress");
        Object latRaw = body.get("lat");
        Object lonRaw = body.get("lon");
        Double lat = latRaw == null ? null : Double.valueOf(latRaw.toString());
        Double lon = lonRaw == null ? null : Double.valueOf(lonRaw.toString());
        settingsService.setDepotAddress(address, lat, lon);
        return get();
    }
}
