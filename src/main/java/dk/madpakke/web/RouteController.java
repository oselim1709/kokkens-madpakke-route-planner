package dk.madpakke.web;

import dk.madpakke.domain.Driver;
import dk.madpakke.domain.Route;
import dk.madpakke.domain.Stop;
import dk.madpakke.repository.DriverRepository;
import dk.madpakke.repository.RouteRepository;
import dk.madpakke.service.GoogleMapsUrlBuilder;
import dk.madpakke.service.RouteGenerationResult;
import dk.madpakke.service.RouteGenerationService;
import dk.madpakke.service.RouteTextFormatter;
import dk.madpakke.service.SettingsService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteGenerationService routeGenerationService;
    private final RouteRepository routeRepository;
    private final RouteTextFormatter routeTextFormatter;
    private final SettingsService settingsService;
    private final DriverRepository driverRepository;

    public RouteController(RouteGenerationService routeGenerationService,
                            RouteRepository routeRepository,
                            RouteTextFormatter routeTextFormatter,
                            SettingsService settingsService,
                            DriverRepository driverRepository) {
        this.routeGenerationService = routeGenerationService;
        this.routeRepository = routeRepository;
        this.routeTextFormatter = routeTextFormatter;
        this.settingsService = settingsService;
        this.driverRepository = driverRepository;
    }

    /**
     * Routes loaded from the DB don't carry a map link or end address (not persisted) — fill them
     * in from the current depot and the assigned driver's current end address.
     */
    private void attachMapUrl(Route route) {
        String endAddress = route.getDriverId() == null ? null
            : driverRepository.findById(route.getDriverId()).map(Driver::getEndAddress).orElse(null);
        route.setEndAddress(endAddress == null || endAddress.isBlank() ? null : endAddress.trim());
        if (settingsService.hasDepotCoordinates()) {
            route.setGoogleMapsUrl(GoogleMapsUrlBuilder.build(settingsService.getDepotAddress(), route.getStops(), route.getEndAddress()));
            route.setGoogleMapsExcludedStopCount(GoogleMapsUrlBuilder.excludedStopCount(route.getStops(), route.getEndAddress()));
        }
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate() {
        try {
            RouteGenerationResult result = routeGenerationService.generate();
            return ResponseEntity.ok(Map.of(
                "routes", result.routes(),
                "skippedStops", result.skippedStops()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/today")
    public List<Route> today() {
        List<Route> routes = routeRepository.findByDate(LocalDate.now().toString());
        routes.forEach(this::attachMapUrl);
        return routes;
    }

    /** One route's new stop list, in driving order. */
    public record RouteStops(long routeId, List<Long> stopIds) {
    }

    public record RouteLayout(List<RouteStops> routes) {
    }

    /**
     * Manually sets which stops each of today's routes has, and their order — e.g. to move a few
     * stops from one driver to another while leaving everything else as it was. It must cover
     * exactly today's routes and exactly today's stops (every stop once), so nothing can get lost.
     */
    @PutMapping("/layout")
    public ResponseEntity<?> setLayout(@RequestBody RouteLayout layout) {
        List<Route> current = routeRepository.findByDate(LocalDate.now().toString());
        Set<Long> currentRouteIds = current.stream().map(Route::getId).collect(Collectors.toSet());
        Set<Long> givenRouteIds = layout.routes().stream().map(RouteStops::routeId).collect(Collectors.toSet());
        List<Long> currentStopIds = current.stream().flatMap(r -> r.getStops().stream()).map(Stop::getId).sorted().toList();
        List<Long> givenStopIds = layout.routes().stream().flatMap(r -> r.stopIds().stream()).sorted().toList();
        if (!currentRouteIds.equals(givenRouteIds) || layout.routes().size() != currentRouteIds.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Skal indeholde præcis dagens ruter."));
        }
        if (!currentStopIds.equals(givenStopIds)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Skal indeholde præcis de samme stops som ruterne har nu, hvert stop én gang."));
        }
        routeRepository.replaceStops(layout.routes().stream()
            .collect(Collectors.toMap(RouteStops::routeId, RouteStops::stopIds, (a, b) -> a, LinkedHashMap::new)));
        // Times depend on the stops and their order, so recompute them for the drivers' routes.
        for (Route route : routeRepository.findByDate(LocalDate.now().toString())) {
            Driver driver = route.getDriverId() == null ? null : driverRepository.findById(route.getDriverId()).orElse(null);
            double minutes = routeGenerationService.estimateMinutes(route.getStops(), driver);
            if (minutes >= 0) {
                routeRepository.updateEstimatedMinutes(route.getId(), minutes);
            }
        }
        return ResponseEntity.ok(today());
    }

    @PutMapping("/{id}/driver")
    public ResponseEntity<Void> assignDriver(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object driverIdRaw = body.get("driverId");
        Long driverId = driverIdRaw == null ? null : Long.valueOf(driverIdRaw.toString());
        routeRepository.assignDriver(id, driverId);
        // A different driver can mean a different end address, so the total time changes too.
        routeRepository.findById(id).ifPresent(route -> {
            Driver driver = driverId == null ? null : driverRepository.findById(driverId).orElse(null);
            double minutes = routeGenerationService.estimateMinutes(route.getStops(), driver);
            if (minutes >= 0) {
                routeRepository.updateEstimatedMinutes(id, minutes);
            }
        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> text(@PathVariable long id) {
        return routeRepository.findById(id)
            .map(route -> {
                attachMapUrl(route);
                return ResponseEntity.ok(routeTextFormatter.format(route));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll() {
        routeRepository.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
