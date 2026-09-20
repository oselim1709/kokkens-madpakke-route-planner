package dk.madpakke.web;

import dk.madpakke.domain.Stop;
import dk.madpakke.service.GeocodeResult;
import dk.madpakke.service.GeocodingService;
import dk.madpakke.service.SettingsService;
import dk.madpakke.repository.StopRepository;
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
@RequestMapping("/api/stops")
public class StopController {

    private final StopRepository stopRepository;
    private final GeocodingService geocodingService;
    private final SettingsService settingsService;

    public StopController(StopRepository stopRepository, GeocodingService geocodingService,
                          SettingsService settingsService) {
        this.stopRepository = stopRepository;
        this.geocodingService = geocodingService;
        this.settingsService = settingsService;
    }

    /**
     * "Scharlingsvej 21, 3 th" typed straight into the address field: move "3 th" to the floor/door
     * field, so the address (and the Google Maps link built from it) stays clean.
     */
    private void splitFloorDoor(Stop stop) {
        String address = stop.getAddress() == null ? "" : stop.getAddress().trim();
        String cleaned = GeocodingService.stripTrailingFloor(address);
        if (!cleaned.equals(address)) {
            String removed = address.substring(cleaned.length()).replaceFirst("^[,\\s]+", "").trim();
            if (stop.getFloorDoor() == null || stop.getFloorDoor().isBlank()) {
                stop.setFloorDoor(removed);
            }
            stop.setAddress(cleaned);
        } else {
            stop.setAddress(address);
        }
        if (stop.getFloorDoor() != null) {
            stop.setFloorDoor(stop.getFloorDoor().trim());
        }
    }

    /**
     * When the address wasn't picked from the suggestions (no coordinates), look it up now, so the
     * stop doesn't sit there flagged "adresse ikke fundet" until routes are generated. The address
     * text itself is left exactly as typed. If the lookup fails the stop is still saved.
     */
    private void geocodeIfMissing(Stop stop) {
        if (stop.getLat() != null && stop.getLon() != null) {
            return;
        }
        try {
            GeocodeResult near = settingsService.hasDepotCoordinates() ? settingsService.getDepotCoordinates() : null;
            geocodingService.geocode(stop.getAddress(), near).ifPresent(result -> {
                stop.setLat(result.lat());
                stop.setLon(result.lon());
            });
        } catch (Exception e) {
            // Leave the coordinates empty; route generation retries later.
        }
    }

    @GetMapping
    public List<Stop> all() {
        return stopRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<Stop> create(@RequestBody Stop stop) {
        stop.setId(null);
        // The frontend sends coordinates only when the address was picked from the
        // autocomplete dropdown (a confirmed, geocodable address). Otherwise look it up here.
        splitFloorDoor(stop);
        geocodeIfMissing(stop);
        return ResponseEntity.ok(stopRepository.insert(stop));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Stop> update(@PathVariable long id, @RequestBody Stop stop) {
        return stopRepository.findById(id).map(existing -> {
            stop.setId(id);
            splitFloorDoor(stop);
            boolean addressChanged = !existing.getAddress().equals(stop.getAddress());
            boolean coordinatesSupplied = stop.getLat() != null && stop.getLon() != null;
            if (!addressChanged && !coordinatesSupplied) {
                stop.setLat(existing.getLat());
                stop.setLon(existing.getLon());
            }
            geocodeIfMissing(stop);
            stopRepository.update(stop);
            return ResponseEntity.ok(stop);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Quick on/off for one stop ("with in today's routes"), without touching its other data. */
    @PutMapping("/{id}/active")
    public ResponseEntity<Void> setActive(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        stopRepository.setActive(id, Boolean.TRUE.equals(body.get("active")));
        return ResponseEntity.noContent().build();
    }

    /** Select all / deselect all. */
    @PutMapping("/active")
    public ResponseEntity<Void> setAllActive(@RequestBody Map<String, Boolean> body) {
        stopRepository.setAllActive(Boolean.TRUE.equals(body.get("active")));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        stopRepository.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll() {
        stopRepository.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
