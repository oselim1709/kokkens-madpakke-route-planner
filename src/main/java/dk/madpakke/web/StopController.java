package dk.madpakke.web;

import dk.madpakke.domain.Stop;
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

    public StopController(StopRepository stopRepository) {
        this.stopRepository = stopRepository;
    }

    @GetMapping
    public List<Stop> all() {
        return stopRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<Stop> create(@RequestBody Stop stop) {
        stop.setId(null);
        // The frontend sends coordinates only when the address was picked from the
        // autocomplete dropdown (a confirmed, geocodable address). Otherwise leave them
        // null so route generation geocodes it lazily as a fallback.
        return ResponseEntity.ok(stopRepository.insert(stop));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Stop> update(@PathVariable long id, @RequestBody Stop stop) {
        return stopRepository.findById(id).map(existing -> {
            stop.setId(id);
            boolean addressChanged = !existing.getAddress().equals(stop.getAddress());
            boolean coordinatesSupplied = stop.getLat() != null && stop.getLon() != null;
            if (!addressChanged && !coordinatesSupplied) {
                stop.setLat(existing.getLat());
                stop.setLon(existing.getLon());
            }
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
