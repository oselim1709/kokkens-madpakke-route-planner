package dk.madpakke.service;

import dk.madpakke.domain.Driver;
import dk.madpakke.domain.Route;
import dk.madpakke.domain.Stop;
import dk.madpakke.domain.StopType;
import dk.madpakke.repository.DriverRepository;
import dk.madpakke.repository.RouteRepository;
import dk.madpakke.repository.StopRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns today's active stops into a set of balanced driving routes.
 *
 * Heuristic (not an exact solver):
 *  1. Geocode any stops that don't have coordinates yet.
 *  2. Fetch real road-network driving times between the depot and every stop (OSRM),
 *     falling back to straight-line distance × an assumed speed for anything it
 *     couldn't resolve — see {@link TravelTimeMatrix}.
 *  3. If a stop was pinned to a specific driver, it always ends up on that driver's
 *     route. Otherwise, stops are swept by compass angle around the depot and split
 *     into contiguous, roughly on-site-time-balanced groups (one per driver) — a
 *     contiguous angular slice keeps each route geographically coherent to start.
 *     When some stops ARE pinned, the remaining unpinned stops are instead handed one
 *     at a time to whichever driver currently has the least committed time.
 *  4. A rebalancing pass then moves individual (unpinned) stops from the currently
 *     busiest route to the lightest one, using the real driving-time totals, as long
 *     as that actually shrinks the gap — this is what corrects the cases where the
 *     initial geography-based split guessed wrong (a route that looked compact on a
 *     map can still be slower in real traffic/roads than a route that looked spread out).
 *  5. Inside each route, stops with a delivery deadline go first (earliest deadline
 *     first); the rest are ordered with a nearest-neighbour walk using real driving times.
 */
@Service
public class RouteGenerationService {

    private static final int MAX_REBALANCE_MOVES = 12;
    private static final double REBALANCE_THRESHOLD_MINUTES = 15;

    private final StopRepository stopRepository;
    private final DriverRepository driverRepository;
    private final RouteRepository routeRepository;
    private final GeocodingService geocodingService;
    private final SettingsService settingsService;
    private final RoutingService routingService;

    private final int gymMinutes;
    private final int privateMinutes;
    private final double avgSpeedKmh;

    public RouteGenerationService(StopRepository stopRepository,
                                   DriverRepository driverRepository,
                                   RouteRepository routeRepository,
                                   GeocodingService geocodingService,
                                   SettingsService settingsService,
                                   RoutingService routingService,
                                   @Value("${madpakke.onsite-minutes.gym}") int gymMinutes,
                                   @Value("${madpakke.onsite-minutes.private}") int privateMinutes,
                                   @Value("${madpakke.avg-speed-kmh}") double avgSpeedKmh) {
        this.stopRepository = stopRepository;
        this.driverRepository = driverRepository;
        this.routeRepository = routeRepository;
        this.geocodingService = geocodingService;
        this.settingsService = settingsService;
        this.routingService = routingService;
        this.gymMinutes = gymMinutes;
        this.privateMinutes = privateMinutes;
        this.avgSpeedKmh = avgSpeedKmh;
    }

    public RouteGenerationResult generate() {
        List<Stop> activeStops = stopRepository.findActive();
        String today = LocalDate.now().toString();

        if (activeStops.isEmpty()) {
            routeRepository.replaceRoutesForDate(today, List.of());
            return new RouteGenerationResult(List.of(), List.of());
        }

        GeocodeResult depot = settingsService.getDepotCoordinates();

        List<String> skipped = new ArrayList<>();
        List<Stop> geocodable = new ArrayList<>();
        for (Stop stop : activeStops) {
            if (!stop.isGeocoded()) {
                // Bias ambiguous/relaxed matches (e.g. a street name that exists in several
                // Danish towns) towards whichever is actually near where deliveries happen.
                Optional<GeocodeResult> result = geocodingService.geocode(stop.getAddress(), depot);
                if (result.isPresent()) {
                    stopRepository.updateGeocode(stop.getId(), result.get().lat(), result.get().lon());
                    stop.setLat(result.get().lat());
                    stop.setLon(result.get().lon());
                }
            }
            if (stop.isGeocoded()) {
                geocodable.add(stop);
            } else {
                skipped.add(stop.getCustomerName() + " (" + stop.getAddress() + ")");
            }
        }

        if (geocodable.isEmpty()) {
            routeRepository.replaceRoutesForDate(today, List.of());
            return new RouteGenerationResult(List.of(), skipped);
        }

        List<Driver> drivers = driverRepository.findAll();
        String depotAddress = settingsService.getDepotAddress();
        TravelTimeMatrix travelTimes = TravelTimeMatrix.build(depot, geocodable, routingService, avgSpeedKmh);

        List<Route> routes = drivers.isEmpty()
            ? buildRoutesWithoutDrivers(geocodable, depot, travelTimes, depotAddress, today)
            : buildRoutesForDrivers(geocodable, drivers, depot, travelTimes, depotAddress, today);

        routeRepository.replaceRoutesForDate(today, routes);
        return new RouteGenerationResult(routes, skipped);
    }

    private List<Route> buildRoutesWithoutDrivers(List<Stop> geocodable, GeocodeResult depot,
                                                    TravelTimeMatrix travelTimes, String depotAddress, String today) {
        List<Stop> swept = sweepByAngle(geocodable, depot);
        List<List<Stop>> buckets = splitIntoBalancedGroups(swept, 1);
        return buildRoutesFromBuckets(buckets, null, travelTimes, depotAddress, today);
    }

    private List<Route> buildRoutesForDrivers(List<Stop> geocodable, List<Driver> drivers, GeocodeResult depot,
                                               TravelTimeMatrix travelTimes, String depotAddress, String today) {
        Set<Long> driverIds = drivers.stream().map(Driver::getId).collect(Collectors.toSet());
        boolean anyPinned = geocodable.stream()
            .anyMatch(s -> s.getPreferredDriverId() != null && driverIds.contains(s.getPreferredDriverId()));

        if (!anyPinned) {
            int numRoutes = Math.max(1, Math.min(drivers.size(), geocodable.size()));
            List<Stop> swept = sweepByAngle(geocodable, depot);
            List<List<Stop>> buckets = splitIntoBalancedGroups(swept, numRoutes);
            List<Route> routes = buildRoutesFromBuckets(buckets, drivers, travelTimes, depotAddress, today);
            rebalanceByRealTime(routes, travelTimes, depotAddress);
            return routes;
        }

        Map<Long, List<Stop>> byDriver = new LinkedHashMap<>();
        for (Driver driver : drivers) {
            byDriver.put(driver.getId(), new ArrayList<>());
        }
        List<Stop> unpinned = new ArrayList<>();
        for (Stop stop : geocodable) {
            Long preferred = stop.getPreferredDriverId();
            if (preferred != null && byDriver.containsKey(preferred)) {
                byDriver.get(preferred).add(stop);
            } else {
                unpinned.add(stop);
            }
        }

        double[] committedMinutes = new double[drivers.size()];
        for (int i = 0; i < drivers.size(); i++) {
            committedMinutes[i] = byDriver.get(drivers.get(i).getId()).stream()
                .mapToDouble(this::onSiteMinutes).sum();
        }

        // Unpinned stops go one at a time to whichever driver is currently least loaded,
        // so a driver who already has pinned stops doesn't get overloaded on top of them.
        for (Stop stop : sweepByAngle(unpinned, depot)) {
            int lightest = 0;
            for (int i = 1; i < committedMinutes.length; i++) {
                if (committedMinutes[i] < committedMinutes[lightest]) {
                    lightest = i;
                }
            }
            byDriver.get(drivers.get(lightest).getId()).add(stop);
            committedMinutes[lightest] += onSiteMinutes(stop);
        }

        List<Route> routes = new ArrayList<>();
        int sequenceIndex = 0;
        for (Driver driver : drivers) {
            List<Stop> stopsForDriver = byDriver.get(driver.getId());
            if (stopsForDriver.isEmpty()) {
                continue;
            }
            List<Stop> ordered = orderStops(stopsForDriver, travelTimes);
            Route route = new Route();
            route.setRouteDate(today);
            route.setSequenceIndex(sequenceIndex++);
            route.setStops(ordered);
            route.setEstimatedMinutes(estimateRouteMinutes(ordered, travelTimes));
            route.setGoogleMapsUrl(GoogleMapsUrlBuilder.build(depotAddress, ordered));
            route.setGoogleMapsExcludedStopCount(GoogleMapsUrlBuilder.excludedStopCount(ordered));
            route.setDriverId(driver.getId());
            route.setDriverName(driver.getName());
            routes.add(route);
        }
        rebalanceByRealTime(routes, travelTimes, depotAddress);
        return routes;
    }

    private List<Route> buildRoutesFromBuckets(List<List<Stop>> buckets, List<Driver> drivers,
                                                TravelTimeMatrix travelTimes, String depotAddress, String today) {
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            List<Stop> bucket = buckets.get(i);
            if (bucket.isEmpty()) {
                continue;
            }
            List<Stop> ordered = orderStops(bucket, travelTimes);

            Route route = new Route();
            route.setRouteDate(today);
            route.setSequenceIndex(i);
            route.setStops(ordered);
            route.setEstimatedMinutes(estimateRouteMinutes(ordered, travelTimes));
            route.setGoogleMapsUrl(GoogleMapsUrlBuilder.build(depotAddress, ordered));
            route.setGoogleMapsExcludedStopCount(GoogleMapsUrlBuilder.excludedStopCount(ordered));
            if (drivers != null && i < drivers.size()) {
                Driver driver = drivers.get(i);
                route.setDriverId(driver.getId());
                route.setDriverName(driver.getName());
            }
            routes.add(route);
        }
        return routes;
    }

    /**
     * Greedily moves one stop at a time from the currently slowest route to the fastest,
     * using real driving times, whenever that actually shrinks the gap between them.
     * Never moves a stop that's pinned to the route's own driver, and never fully empties
     * a route. Stops once the gap is small enough or no further move helps.
     */
    private void rebalanceByRealTime(List<Route> routes, TravelTimeMatrix travelTimes, String depotAddress) {
        if (routes.size() < 2) {
            return;
        }
        for (int iteration = 0; iteration < MAX_REBALANCE_MOVES; iteration++) {
            Route heaviest = routes.stream().max(Comparator.comparingDouble(Route::getEstimatedMinutes)).orElseThrow();
            Route lightest = routes.stream()
                .filter(r -> r != heaviest)
                .min(Comparator.comparingDouble(Route::getEstimatedMinutes))
                .orElse(null);
            if (lightest == null) {
                return;
            }
            double currentGap = heaviest.getEstimatedMinutes() - lightest.getEstimatedMinutes();
            if (currentGap < REBALANCE_THRESHOLD_MINUTES) {
                return;
            }

            Stop bestCandidate = null;
            double bestGap = currentGap;
            List<Stop> bestHeavyOrdered = null;
            List<Stop> bestLightOrdered = null;
            double bestHeavyMinutes = 0;
            double bestLightMinutes = 0;

            if (heaviest.getStops().size() > 1) {
                for (Stop candidate : heaviest.getStops()) {
                    if (isPinnedToRoute(candidate, heaviest)) {
                        continue;
                    }
                    List<Stop> newHeavyStops = new ArrayList<>(heaviest.getStops());
                    newHeavyStops.remove(candidate);
                    List<Stop> newLightStops = new ArrayList<>(lightest.getStops());
                    newLightStops.add(candidate);

                    List<Stop> heavyOrdered = orderStops(newHeavyStops, travelTimes);
                    List<Stop> lightOrdered = orderStops(newLightStops, travelTimes);
                    double heavyMinutes = estimateRouteMinutes(heavyOrdered, travelTimes);
                    double lightMinutes = estimateRouteMinutes(lightOrdered, travelTimes);
                    double newGap = Math.abs(heavyMinutes - lightMinutes);

                    if (newGap < bestGap) {
                        bestGap = newGap;
                        bestCandidate = candidate;
                        bestHeavyOrdered = heavyOrdered;
                        bestLightOrdered = lightOrdered;
                        bestHeavyMinutes = heavyMinutes;
                        bestLightMinutes = lightMinutes;
                    }
                }
            }

            if (bestCandidate == null) {
                return;
            }

            heaviest.setStops(bestHeavyOrdered);
            heaviest.setEstimatedMinutes(bestHeavyMinutes);
            heaviest.setGoogleMapsUrl(GoogleMapsUrlBuilder.build(depotAddress, bestHeavyOrdered));
            heaviest.setGoogleMapsExcludedStopCount(GoogleMapsUrlBuilder.excludedStopCount(bestHeavyOrdered));

            lightest.setStops(bestLightOrdered);
            lightest.setEstimatedMinutes(bestLightMinutes);
            lightest.setGoogleMapsUrl(GoogleMapsUrlBuilder.build(depotAddress, bestLightOrdered));
            lightest.setGoogleMapsExcludedStopCount(GoogleMapsUrlBuilder.excludedStopCount(bestLightOrdered));
        }
    }

    private boolean isPinnedToRoute(Stop stop, Route route) {
        return stop.getPreferredDriverId() != null && stop.getPreferredDriverId().equals(route.getDriverId());
    }

    private List<Stop> sweepByAngle(List<Stop> stops, GeocodeResult depot) {
        return stops.stream()
            .sorted(Comparator.comparingDouble(s ->
                DistanceUtil.angleFromDepotDegrees(depot.lat(), depot.lon(), s.getLat(), s.getLon())))
            .collect(Collectors.toList());
    }

    /** Splits an angle-swept stop list into contiguous groups with roughly equal on-site workload. */
    private List<List<Stop>> splitIntoBalancedGroups(List<Stop> sweptStops, int numRoutes) {
        List<List<Stop>> buckets = new ArrayList<>();
        for (int i = 0; i < numRoutes; i++) {
            buckets.add(new ArrayList<>());
        }
        if (sweptStops.isEmpty()) {
            return buckets;
        }

        double totalWeight = sweptStops.stream().mapToDouble(this::onSiteMinutes).sum();
        double perBucketTarget = totalWeight / numRoutes;

        int bucketIndex = 0;
        double bucketAccum = 0;
        int n = sweptStops.size();

        for (int i = 0; i < n; i++) {
            Stop stop = sweptStops.get(i);
            int remainingStops = n - i;
            int remainingBuckets = numRoutes - bucketIndex;
            // With exactly as many stops left as buckets left, we must give each remaining
            // bucket one stop from here on, regardless of accumulated weight, or a later
            // bucket would end up empty (e.g. one very heavy stop skewing the target).
            boolean mustSpreadOneEach = remainingStops <= remainingBuckets;
            boolean bucketHasContent = !buckets.get(bucketIndex).isEmpty();
            boolean targetReached = bucketAccum >= perBucketTarget;

            if (bucketIndex < numRoutes - 1 && bucketHasContent && (targetReached || mustSpreadOneEach)) {
                bucketIndex++;
                bucketAccum = 0;
            }
            buckets.get(bucketIndex).add(stop);
            bucketAccum += onSiteMinutes(stop);
        }
        return buckets;
    }

    /** Deadline stops first (earliest first), then a nearest-neighbour walk using real driving times. */
    private List<Stop> orderStops(List<Stop> stops, TravelTimeMatrix travelTimes) {
        List<Stop> withDeadline = stops.stream()
            .filter(s -> s.getDeadline() != null)
            .sorted(Comparator.comparing(Stop::getDeadline))
            .collect(Collectors.toList());
        List<Stop> remaining = new ArrayList<>(stops.stream()
            .filter(s -> s.getDeadline() == null)
            .toList());

        List<Stop> ordered = new ArrayList<>(withDeadline);
        Stop current = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);

        while (!remaining.isEmpty()) {
            Stop from = current;
            Stop nearest = remaining.stream()
                .min(Comparator.comparingDouble(s -> from == null ? travelTimes.fromDepot(s) : travelTimes.between(from, s)))
                .orElseThrow();
            remaining.remove(nearest);
            ordered.add(nearest);
            current = nearest;
        }
        return ordered;
    }

    /** Depot is only the route's starting point — no return leg is budgeted or navigated back to it. */
    private double estimateRouteMinutes(List<Stop> orderedStops, TravelTimeMatrix travelTimes) {
        double minutes = 0;
        Stop previous = null;
        for (Stop stop : orderedStops) {
            minutes += previous == null ? travelTimes.fromDepot(stop) : travelTimes.between(previous, stop);
            minutes += onSiteMinutes(stop);
            previous = stop;
        }
        return minutes;
    }

    private int onSiteMinutes(Stop stop) {
        return stop.getStopType() == StopType.GYM ? gymMinutes : privateMinutes;
    }
}
