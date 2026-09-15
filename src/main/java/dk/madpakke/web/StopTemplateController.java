package dk.madpakke.web;

import dk.madpakke.domain.Stop;
import dk.madpakke.domain.StopTemplate;
import dk.madpakke.repository.StopRepository;
import dk.madpakke.repository.StopTemplateRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Named, reusable stop-list snapshots ("faste stops") — save the usual delivery list once, reuse it. */
@RestController
@RequestMapping("/api/templates")
public class StopTemplateController {

    private final StopTemplateRepository templateRepository;
    private final StopRepository stopRepository;

    public StopTemplateController(StopTemplateRepository templateRepository, StopRepository stopRepository) {
        this.templateRepository = templateRepository;
        this.stopRepository = stopRepository;
    }

    @GetMapping
    public List<StopTemplate> all() {
        return templateRepository.findAllSummaries();
    }

    @GetMapping("/{id}")
    public ResponseEntity<StopTemplate> get(@PathVariable long id) {
        return templateRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** Saves a snapshot of the given stops (as they are right now) under a new template name. */
    @PostMapping
    public ResponseEntity<StopTemplate> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("stopIds");
        List<Long> stopIds = rawIds == null ? List.of() : rawIds.stream().map(Number::longValue).toList();
        List<Stop> stops = stopRepository.findByIds(stopIds);
        if (stops.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(templateRepository.insert(name.trim(), stops));
    }

    /** Clones every item in the template into new, active stops. */
    @PostMapping("/{id}/apply")
    public ResponseEntity<List<Stop>> apply(@PathVariable long id) {
        return templateRepository.findById(id)
            .map(template -> {
                List<Stop> created = template.getItems().stream()
                    .map(item -> {
                        Stop copy = new Stop();
                        copy.setCustomerName(item.getCustomerName());
                        copy.setAddress(item.getAddress());
                        copy.setLat(item.getLat());
                        copy.setLon(item.getLon());
                        copy.setStopType(item.getStopType());
                        copy.setDeadline(item.getDeadline());
                        copy.setQtyNormalLunchbox(item.getQtyNormalLunchbox());
                        copy.setQtyFitnessLunchbox(item.getQtyFitnessLunchbox());
                        copy.setQtyMusliBar(item.getQtyMusliBar());
                        copy.setQtyFruit(item.getQtyFruit());
                        copy.setQtyRisengroed(item.getQtyRisengroed());
                        copy.setQtySandwich(item.getQtySandwich());
                        copy.setQtyCake(item.getQtyCake());
                        copy.setSpecialOrder(item.getSpecialOrder());
                        copy.setPreferredDriverId(item.getPreferredDriverId());
                        copy.setActive(true);
                        return stopRepository.insert(copy);
                    })
                    .toList();
                return ResponseEntity.ok(created);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        templateRepository.delete(id);
        return ResponseEntity.noContent().build();
    }
}
