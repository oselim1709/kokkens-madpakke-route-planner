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
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("depotAddress", settingsService.getDepotAddress());
        result.put("depotGeocoded", settingsService.hasDepotCoordinates());
        result.put("usingGoogleGeocoding", geocodingService.usingGoogle());
        result.put("routeStartTime", settingsService.getRouteStartTime().map(Object::toString).orElse(null));
        return result;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        if (body.containsKey("depotAddress")) {
            String address = (String) body.get("depotAddress");
            Object latRaw = body.get("lat");
            Object lonRaw = body.get("lon");
            Double lat = latRaw == null ? null : Double.valueOf(latRaw.toString());
            Double lon = lonRaw == null ? null : Double.valueOf(lonRaw.toString());
            settingsService.setDepotAddress(address, lat, lon);
        }
        if (body.containsKey("routeStartTime")) {
            Object raw = body.get("routeStartTime");
            settingsService.setRouteStartTime(raw == null || raw.toString().isBlank()
                ? null : java.time.LocalTime.parse(raw.toString()));
        }
        return get();
    }
}
