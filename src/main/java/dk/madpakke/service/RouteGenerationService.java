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
import java.util.Objects;
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
 *  3. If a stop was pinned to a specific driver, it always ends up on that driver's route,
 *     inserted wherever it causes the least extra driving. Otherwise, with no pins at all,
 *     stops are swept by compass angle around the depot and split into contiguous, roughly
 *     on-site-time-balanced groups (one per driver) — a contiguous angular slice keeps each
 *     route geographically coherent to start. When some stops ARE pinned, the remaining
 *     unpinned stops are instead each inserted into whichever driver's route — and which
 *     position in it — actually costs the least extra real driving time, one stop at a time.
 *     This replaces handing them out by a rough per-stop time guess that ignored geography
 *     entirely and could load up one driver with a whole cluster of far-flung stops while the
 *     other sat underloaded.
 *  4. A "does this stop actually belong here" pass then moves any unpinned stop to a different
 *     driver's route whenever that route can pick it up for clearly less extra driving than its
 *     current route spends reaching it — the real detour its immediate neighbours (or the depot
 *     / the driver's end address) cause, not a full re-walk of the route. This is what fixes the
 *     angle-sweep's boundary mistakes: a stop can be geographically right next to another
 *     driver's stops, yet fall just on the wrong side of the angular cut in step 3. It only
 *     moves a stop for a clear efficiency win — it does not try to equalize the total time
 *     between drivers, so one route ending up longer than another is expected and left alone
 *     as long as it makes sense on the map.
 *
 * Deadlines are handled one of two ways, depending on whether a start time is set
 * (Indstillinger → Ruter):
 *  - Start time set: deadlines never affect stop order or which driver gets a stop — routes are
 *    built for efficiency regardless. Instead, once a route is built, {@link #attachArrivalTimes}
 *    computes an expected clock time at each stop from the start time and the real driving/
 *    on-site time leading up to it, so a deadline that won't be met shows up as a plain,
 *    checkable fact (with a warning) rather than silently reshaping the route. If one really is
 *    at risk, drag the stop earlier (see Ruter tab).
 *  - No start time set: there's no way to check whether a deadline will actually be met, so as a
 *    safety net every deadline stop is kept in ascending-deadline order (the one thing never
 *    reshuffled) and never moved to a different driver — same as before arrival times existed.
 *    This is also why always forcing every deadline stop first (the old default) got replaced:
 *    with a known start time it's unnecessary and was costing one real route ~20 minutes of pure
 *    detour for a routine end-of-day cutoff hours away; without one, order (not position 1) is
 *    still protected.
 */
@Service
public class RouteGenerationService {

    private static final int MAX_REASSIGN_MOVES = 20;
    // How much a move has to clearly save (the driving time it removes from one route minus
    // what it adds to the other) before it's worth relocating a stop for. Keeps the pass from
    // shuffling stops back and forth over a one- or two-minute difference.
    private static final double MIN_REASSIGN_SAVINGS_MINUTES = 5;

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

        // Only drivers switched on for the day get routes; stops pinned to an inactive driver are spread like unpinned ones.
        List<Driver> drivers = driverRepository.findActive();
        String depotAddress = settingsService.getDepotAddress();
        List<GeocodeResult> endPoints = drivers.stream().map(this::endPointOf).filter(Objects::nonNull).distinct().toList();
        TravelTimeMatrix travelTimes = TravelTimeMatrix.build(depot, geocodable, endPoints, routingService, avgSpeedKmh);

        // No start time means there's no way to check a deadline will actually be met, so deadline
        // order is enforced as a safety net instead — see the class doc comment.
        boolean deadlinesConstrained = settingsService.getRouteStartTime().isEmpty();

        List<Route> routes = drivers.isEmpty()
            ? buildRoutesWithoutDrivers(geocodable, depot, travelTimes, depotAddress, today, deadlinesConstrained)
            : buildRoutesForDrivers(geocodable, drivers, depot, travelTimes, depotAddress, today, deadlinesConstrained);

        attachArrivalTimes(routes, travelTimes, drivers.stream().collect(Collectors.toMap(Driver::getId, d -> d)));
        routeRepository.replaceRoutesForDate(today, routes);
        return new RouteGenerationResult(routes, skipped);
    }

    /**
     * Fills in each stop's expected clock time of arrival — purely for display, from the start
     * time set in Indstillinger plus real driving/on-site time leading up to it. Leaves every
     * stop's {@code arrivalTime} null (and does nothing else) when no start time is set.
     */
    private void attachArrivalTimes(List<Route> routes, TravelTimeMatrix travelTimes, Map<Long, Driver> driversById) {
        settingsService.getRouteStartTime().ifPresent(startTime -> {
            for (Route route : routes) {
                GeocodeResult end = endPointOf(driversById.get(route.getDriverId()));
                double minutes = 0;
                Stop previous = null;
                for (Stop stop : route.getStops()) {
                    minutes += previous == null ? travelTimes.fromDepot(stop) : travelTimes.between(previous, stop);
                    stop.setArrivalTime(startTime.plusMinutes(Math.round(minutes)));
                    minutes += onSiteMinutes(stop);
                    previous = stop;
                }
            }
        });
    }

    /**
     * Same as the private overload, for callers that only have already-persisted routes (no
     * {@link TravelTimeMatrix} on hand) — builds one covering exactly these routes' stops.
     * Safe to call on every read; does nothing if no start time is set, the depot address isn't,
     * or any stop isn't geocoded.
     */
    public void attachArrivalTimes(List<Route> routes) {
        if (routes.isEmpty() || !settingsService.hasDepotCoordinates() || settingsService.getRouteStartTime().isEmpty()) {
            return;
        }
        List<Stop> allStops = new ArrayList<>();
        for (Route route : routes) {
            allStops.addAll(route.getStops());
        }
        if (allStops.isEmpty() || allStops.stream().anyMatch(s -> !s.isGeocoded())) {
            return;
        }
        Map<Long, Driver> driversById = routes.stream()
            .map(Route::getDriverId)
            .filter(Objects::nonNull)
            .distinct()
            .map(driverRepository::findById)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toMap(Driver::getId, d -> d));
        GeocodeResult depot = settingsService.getDepotCoordinates();
        List<GeocodeResult> endPoints = driversById.values().stream().map(this::endPointOf).filter(Objects::nonNull).distinct().toList();
        TravelTimeMatrix travelTimes = TravelTimeMatrix.build(depot, allStops, endPoints, routingService, avgSpeedKmh);
        attachArrivalTimes(routes, travelTimes, driversById);
    }

    private List<Route> buildRoutesWithoutDrivers(List<Stop> geocodable, GeocodeResult depot,
                                                    TravelTimeMatrix travelTimes, String depotAddress, String today,
                                                    boolean deadlinesConstrained) {
        List<Stop> swept = sweepByAngle(geocodable, depot);
        List<List<Stop>> buckets = splitIntoBalancedGroups(swept, 1);
        return buildRoutesFromBuckets(buckets, null, travelTimes, depotAddress, today, deadlinesConstrained);
    }

    private List<Route> buildRoutesForDrivers(List<Stop> geocodable, List<Driver> drivers, GeocodeResult depot,
                                               TravelTimeMatrix travelTimes, String depotAddress, String today,
                                               boolean deadlinesConstrained) {
        Set<Long> driverIds = drivers.stream().map(Driver::getId).collect(Collectors.toSet());
        boolean anyPinned = geocodable.stream()
            .anyMatch(s -> s.getPreferredDriverId() != null && driverIds.contains(s.getPreferredDriverId()));

        if (!anyPinned) {
            int numRoutes = Math.max(1, Math.min(drivers.size(), geocodable.size()));
            List<Stop> swept = sweepByAngle(geocodable, depot);
            List<List<Stop>> buckets = splitIntoBalancedGroups(swept, numRoutes);
            List<Route> routes = buildRoutesFromBuckets(buckets, drivers, travelTimes, depotAddress, today, deadlinesConstrained);
            reassignMisplacedStops(routes, drivers, travelTimes, depotAddress, deadlinesConstrained);
            return routes;
        }

        Map<Long, List<Stop>> byDriver = new LinkedHashMap<>();
        Map<Long, GeocodeResult> endByDriverId = new LinkedHashMap<>();
        for (Driver driver : drivers) {
            byDriver.put(driver.getId(), new ArrayList<>());
            endByDriverId.put(driver.getId(), endPointOf(driver));
        }

        List<Stop> pinned = new ArrayList<>();
        List<Stop> unpinned = new ArrayList<>();
        for (Stop stop : geocodable) {
            Long preferred = stop.getPreferredDriverId();
            (preferred != null && byDriver.containsKey(preferred) ? pinned : unpinned).add(stop);
        }

        if (deadlinesConstrained) {
            // Pass 1: every deadline stop (pinned or not) is placed in global ascending-deadline
            // order — appending each to its driver's route-so-far is always deadline-valid, since
            // every deadline stop already placed anywhere has an equal-or-earlier deadline. A
            // pinned one goes straight to its driver; an unpinned one goes wherever that append
            // is cheapest.
            List<Stop> deadlineStops = geocodable.stream()
                .filter(s -> s.getDeadline() != null)
                .sorted(Comparator.comparing(Stop::getDeadline))
                .toList();
            for (Stop stop : deadlineStops) {
                Long preferred = stop.getPreferredDriverId();
                if (preferred != null && byDriver.containsKey(preferred)) {
                    byDriver.get(preferred).add(stop);
                    continue;
                }
                Long bestDriverId = null;
                double bestCost = Double.MAX_VALUE;
                for (Driver driver : drivers) {
                    List<Stop> current = byDriver.get(driver.getId());
                    double cost = insertionCost(current, current.size(), stop, travelTimes, endByDriverId.get(driver.getId()));
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestDriverId = driver.getId();
                    }
                }
                byDriver.get(bestDriverId).add(stop);
            }
            pinned = pinned.stream().filter(s -> s.getDeadline() == null).toList();
            unpinned = unpinned.stream().filter(s -> s.getDeadline() == null).toList();
        }

        // Pinned, non-deadline-constrained stops are inserted into their own driver's route
        // wherever that costs the least extra driving — may land before, between or after any
        // deadline stops placed above, since only their relative order (if constrained) is fixed.
        for (Stop stop : pinned) {
            Long preferred = stop.getPreferredDriverId();
            List<Stop> current = byDriver.get(preferred);
            int bestPos = 0;
            double bestCost = Double.MAX_VALUE;
            for (int pos = 0; pos <= current.size(); pos++) {
                double cost = insertionCost(current, pos, stop, travelTimes, endByDriverId.get(preferred));
                if (cost < bestCost) {
                    bestCost = cost;
                    bestPos = pos;
                }
            }
            current.add(bestPos, stop);
        }

        // Unpinned stops — the ones that used to go by a rough per-stop time guess — now each go
        // wherever (which driver, which position) is the real cheapest insertion, so a driver
        // already carrying a far-flung cluster stops absorbing more of it just because its
        // on-site-minutes tally looked lighter.
        for (Stop stop : sweepByAngle(unpinned, depot)) {
            Long bestDriverId = null;
            int bestPos = 0;
            double bestCost = Double.MAX_VALUE;
            for (Driver driver : drivers) {
                List<Stop> current = byDriver.get(driver.getId());
                GeocodeResult end = endByDriverId.get(driver.getId());
                for (int pos = 0; pos <= current.size(); pos++) {
                    double cost = insertionCost(current, pos, stop, travelTimes, end);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestDriverId = driver.getId();
                        bestPos = pos;
                    }
                }
            }
            byDriver.get(bestDriverId).add(bestPos, stop);
        }

        List<Route> routes = new ArrayList<>();
        int sequenceIndex = 0;
        for (Driver driver : drivers) {
            List<Stop> stopsForDriver = byDriver.get(driver.getId());
            if (stopsForDriver.isEmpty()) {
                continue;
            }
            Route route = new Route();
            route.setRouteDate(today);
            route.setSequenceIndex(sequenceIndex++);
            route.setDriverId(driver.getId());
            route.setDriverName(driver.getName());
            applyStops(route, stopsForDriver, driver, travelTimes, depotAddress);
            routes.add(route);
        }
        reassignMisplacedStops(routes, drivers, travelTimes, depotAddress, deadlinesConstrained);
        return routes;
    }

    private List<Route> buildRoutesFromBuckets(List<List<Stop>> buckets, List<Driver> drivers,
                                                TravelTimeMatrix travelTimes, String depotAddress, String today,
                                                boolean deadlinesConstrained) {
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            List<Stop> bucket = buckets.get(i);
            if (bucket.isEmpty()) {
                continue;
            }
            Driver driver = drivers != null && i < drivers.size() ? drivers.get(i) : null;
            List<Stop> ordered = orderStops(bucket, travelTimes, endPointOf(driver), deadlinesConstrained);

            Route route = new Route();
            route.setRouteDate(today);
            route.setSequenceIndex(i);
            if (driver != null) {
                route.setDriverId(driver.getId());
                route.setDriverName(driver.getName());
            }
            applyStops(route, ordered, driver, travelTimes, depotAddress);
            routes.add(route);
        }
        return routes;
    }

    /**
     * Moves a stop to a different driver's route whenever that route can pick it up for clearly
     * less extra driving than its current route spends reaching it, regardless of the current
     * time split between drivers — this is a geography/efficiency fix, not a fairness one.
     *
     * For every candidate move, "cost" is measured as the actual change in that route's total
     * driving time with the stop optimally re-ordered in (or out), not just distance to its
     * neighbours — so it accounts for the detour the stop causes right where it currently sits.
     * Repeats picking the single best remaining move each round until no move clearly helps,
     * never empties a route, never moves a stop pinned to its current driver, and — when
     * {@code deadlinesConstrained} — never moves a deadline stop either (its relative order is
     * the only thing protecting it when there's no start time to check against).
     */
    private void reassignMisplacedStops(List<Route> routes, List<Driver> drivers, TravelTimeMatrix travelTimes,
                                         String depotAddress, boolean deadlinesConstrained) {
        if (routes.size() < 2) {
            return;
        }
        Map<Long, Driver> driversById = drivers.stream().collect(Collectors.toMap(Driver::getId, d -> d));
        for (int iteration = 0; iteration < MAX_REASSIGN_MOVES; iteration++) {
            Route bestFrom = null;
            Route bestTo = null;
            Stop bestCandidate = null;
            int bestPosition = 0;
            double bestSavings = MIN_REASSIGN_SAVINGS_MINUTES;

            for (Route from : routes) {
                List<Stop> fromStops = from.getStops();
                if (fromStops.size() <= 1) {
                    continue;
                }
                GeocodeResult fromEnd = endPointOf(driversById.get(from.getDriverId()));

                for (int i = 0; i < fromStops.size(); i++) {
                    Stop candidate = fromStops.get(i);
                    if (isPinnedToRoute(candidate, from)
                        || (deadlinesConstrained && candidate.getDeadline() != null)) {
                        continue;
                    }
                    List<Stop> fromWithout = new ArrayList<>(fromStops);
                    fromWithout.remove(i);
                    // The real detour this stop currently costs "from" — what removing it would save.
                    double removalSavings = insertionCost(fromWithout, i, candidate, travelTimes, fromEnd);

                    for (Route to : routes) {
                        if (to == from) {
                            continue;
                        }
                        List<Stop> toStops = to.getStops();
                        GeocodeResult toEnd = endPointOf(driversById.get(to.getDriverId()));
                        for (int pos = 0; pos <= toStops.size(); pos++) {
                            double cost = insertionCost(toStops, pos, candidate, travelTimes, toEnd);
                            double savings = removalSavings - cost;
                            if (savings > bestSavings) {
                                bestSavings = savings;
                                bestFrom = from;
                                bestTo = to;
                                bestCandidate = candidate;
                                bestPosition = pos;
                            }
                        }
                    }
                }
            }

            if (bestFrom == null) {
                return;
            }
            List<Stop> newFromStops = new ArrayList<>(bestFrom.getStops());
            newFromStops.remove(bestCandidate);
            List<Stop> newToStops = new ArrayList<>(bestTo.getStops());
            newToStops.add(bestPosition, bestCandidate);
            applyStops(bestFrom, newFromStops, driversById.get(bestFrom.getDriverId()), travelTimes, depotAddress);
            applyStops(bestTo, newToStops, driversById.get(bestTo.getDriverId()), travelTimes, depotAddress);
        }
    }

    /**
     * The real extra driving time a route incurs by having {@code candidate} sit at {@code gapIndex}
     * within {@code stopsWithoutCandidate} (which must not already contain it) — i.e. the detour
     * caused by its immediate neighbours there (or the depot / the driver's end address, at either
     * end of the route), compared to skipping straight past that gap.
     */
    private double insertionCost(List<Stop> stopsWithoutCandidate, int gapIndex, Stop candidate,
                                  TravelTimeMatrix travelTimes, GeocodeResult end) {
        Stop prevStop = gapIndex > 0 ? stopsWithoutCandidate.get(gapIndex - 1) : null;
        Stop nextStop = gapIndex < stopsWithoutCandidate.size() ? stopsWithoutCandidate.get(gapIndex) : null;

        double toCandidate = prevStop == null ? travelTimes.fromDepot(candidate) : travelTimes.between(prevStop, candidate);
        double fromCandidate = nextStop != null ? travelTimes.between(candidate, nextStop)
            : (end != null ? travelTimes.toEnd(candidate, end) : 0);

        double direct;
        if (prevStop != null) {
            direct = nextStop != null ? travelTimes.between(prevStop, nextStop)
                : (end != null ? travelTimes.toEnd(prevStop, end) : 0);
        } else {
            direct = nextStop != null ? travelTimes.fromDepot(nextStop) : 0;
        }
        return toCandidate + fromCandidate - direct;
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

    /**
     * Builds a route by cheapest insertion: each stop is added wherever it causes the least
     * extra driving (the same real marginal-cost measure as {@link #reassignMisplacedStops}),
     * given everything already placed. When {@code deadlinesConstrained}, deadline stops are
     * placed first in ascending-deadline order (never reordered) and everything else is inserted
     * around them instead — see the class doc comment.
     */
    private List<Stop> orderStops(List<Stop> stops, TravelTimeMatrix travelTimes, GeocodeResult end,
                                   boolean deadlinesConstrained) {
        List<Stop> ordered = deadlinesConstrained
            ? stops.stream().filter(s -> s.getDeadline() != null)
                .sorted(Comparator.comparing(Stop::getDeadline)).collect(Collectors.toCollection(ArrayList::new))
            : new ArrayList<>();
        List<Stop> remaining = deadlinesConstrained
            ? stops.stream().filter(s -> s.getDeadline() == null).toList()
            : stops;
        for (Stop stop : remaining) {
            int bestPos = 0;
            double bestCost = Double.MAX_VALUE;
            for (int pos = 0; pos <= ordered.size(); pos++) {
                double cost = insertionCost(ordered, pos, stop, travelTimes, end);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestPos = pos;
                }
            }
            ordered.add(bestPos, stop);
        }
        return ordered;
    }

    /**
     * The depot is only the route's starting point — no return leg is budgeted or navigated back
     * to it. If the driver has an end address, the drive from the last stop there is included.
     */
    private double estimateRouteMinutes(List<Stop> orderedStops, TravelTimeMatrix travelTimes, GeocodeResult end) {
        double minutes = 0;
        Stop previous = null;
        for (Stop stop : orderedStops) {
            minutes += previous == null ? travelTimes.fromDepot(stop) : travelTimes.between(previous, stop);
            minutes += onSiteMinutes(stop);
            previous = stop;
        }
        if (end != null && previous != null) {
            minutes += travelTimes.toEnd(previous, end);
        }
        return minutes;
    }

    /** Sets a route's stops and everything derived from them for its driver (time, end address, map link). */
    private void applyStops(Route route, List<Stop> ordered, Driver driver, TravelTimeMatrix travelTimes,
                             String depotAddress) {
        String endAddress = endAddressOf(driver);
        route.setStops(ordered);
        route.setEndAddress(endAddress);
        route.setEstimatedMinutes(estimateRouteMinutes(ordered, travelTimes, endPointOf(driver)));
        route.setGoogleMapsUrl(GoogleMapsUrlBuilder.build(depotAddress, ordered, endAddress));
        route.setGoogleMapsExcludedStopCount(GoogleMapsUrlBuilder.excludedStopCount(ordered, endAddress));
    }

    private GeocodeResult endPointOf(Driver driver) {
        return driver != null && driver.isEndGeocoded() ? new GeocodeResult(driver.getEndLat(), driver.getEndLon()) : null;
    }

    private String endAddressOf(Driver driver) {
        return driver != null && driver.getEndAddress() != null && !driver.getEndAddress().isBlank()
            ? driver.getEndAddress().trim() : null;
    }

    /**
     * Driving + on-site time for stops already in their final order, for one driver — used when a
     * route is handed to a different driver afterwards (a different end address changes the total).
     */
    public double estimateMinutes(List<Stop> orderedStops, Driver driver) {
        if (orderedStops.isEmpty() || !settingsService.hasDepotCoordinates()
            || orderedStops.stream().anyMatch(s -> !s.isGeocoded())) {
            return -1;
        }
        GeocodeResult depot = settingsService.getDepotCoordinates();
        GeocodeResult end = endPointOf(driver);
        TravelTimeMatrix travelTimes = TravelTimeMatrix.build(depot, orderedStops,
            end == null ? List.of() : List.of(end), routingService, avgSpeedKmh);
        return estimateRouteMinutes(orderedStops, travelTimes, end);
    }

    private int onSiteMinutes(Stop stop) {
        return stop.getStopType() == StopType.GYM ? gymMinutes : privateMinutes;
    }
}
