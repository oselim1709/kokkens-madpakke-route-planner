package dk.madpakke.web;

import dk.madpakke.service.GeocodeCandidate;
import dk.madpakke.service.GeocodingService;
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

    public GeocodingController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @GetMapping("/search")
    public List<GeocodeCandidate> search(@RequestParam("q") String query) {
        return geocodingService.search(query, 6);
    }
}
