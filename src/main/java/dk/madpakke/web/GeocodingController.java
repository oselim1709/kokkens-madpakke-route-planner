package dk.madpakke.web;

import dk.madpakke.service.GeocodeCandidate;
import dk.madpakke.service.GeocodeResult;
import dk.madpakke.service.GeocodingService;
import dk.madpakke.service.SettingsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Backs the address autocomplete field so users pick a real address instead of typing one that might not match. */
@RestController
@RequestMapping("/api/geocode")
public class GeocodingController {

    private final GeocodingService geocodingService;
    private final SettingsService settingsService;

    public GeocodingController(GeocodingService geocodingService, SettingsService settingsService) {
        this.geocodingService = geocodingService;
        this.settingsService = settingsService;
    }

    @GetMapping("/search")
    public List<GeocodeCandidate> search(@RequestParam("q") String query) {
        // Prefer suggestions near the company's start address when one is set.
        GeocodeResult near = settingsService.hasDepotCoordinates() ? settingsService.getDepotCoordinates() : null;
        return geocodingService.search(query, 6, near);
    }
}
