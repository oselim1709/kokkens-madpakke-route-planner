package dk.madpakke.web;

import dk.madpakke.domain.Driver;
import dk.madpakke.repository.DriverRepository;
import dk.madpakke.service.GeocodeResult;
import dk.madpakke.service.GeocodingService;
import dk.madpakke.service.SettingsService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverRepository driverRepository;
    private final GeocodingService geocodingService;
    private final SettingsService settingsService;

    public DriverController(DriverRepository driverRepository, GeocodingService geocodingService,
                            SettingsService settingsService) {
        this.driverRepository = driverRepository;
        this.geocodingService = geocodingService;
        this.settingsService = settingsService;
    }

    @GetMapping
    public List<Driver> all() {
        return driverRepository.findAll();
    }

    @PostMapping
    public Driver create(@RequestBody Map<String, String> body) {
        return driverRepository.insert(body.get("name"));
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @RequestBody Map<String, String> body) {
        driverRepository.update(id, body.get("name"));
    }

    /**
     * Sets (or, with a blank address, clears) where this driver finishes their route. When the
     * address wasn't picked from the suggestions (no lat/lon), it is looked up here.
     */
    @PutMapping("/{id}/end")
    public ResponseEntity<Driver> setEnd(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String address = body.get("address") == null ? null : body.get("address").toString().trim();
        Double lat = body.get("lat") instanceof Number n ? n.doubleValue() : null;
        Double lon = body.get("lon") instanceof Number n ? n.doubleValue() : null;
        if (address != null && !address.isBlank() && (lat == null || lon == null)) {
            try {
                GeocodeResult near = settingsService.hasDepotCoordinates() ? settingsService.getDepotCoordinates() : null;
                GeocodeResult found = geocodingService.geocode(address, near).orElse(null);
                if (found != null) {
                    lat = found.lat();
                    lon = found.lon();
                }
            } catch (Exception e) {
                // Keep the address without coordinates; the UI flags it.
            }
        }
        driverRepository.setEnd(id, address, lat, lon);
        return driverRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/active")
    public void setActive(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        driverRepository.setActive(id, Boolean.TRUE.equals(body.get("active")));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        driverRepository.delete(id);
    }
}
