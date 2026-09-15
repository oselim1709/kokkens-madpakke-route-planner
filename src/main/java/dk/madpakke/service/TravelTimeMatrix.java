package dk.madpakke.service;

import dk.madpakke.domain.Stop;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Driving time (minutes) between the depot and a set of stops, and between the stops
 * themselves. Backed by a real road-network duration matrix from {@link RoutingService}
 * when available (index 0 is always the depot); falls back to straight-line distance ×
 * an assumed average speed — per pair, not just wholesale — for anything OSRM couldn't
 * reach or didn't return.
 */
final class TravelTimeMatrix {

    private final GeocodeResult depot;
    private final double avgSpeedKmh;
    private final Map<Long, Integer> indexByStopId;
    private final List<Stop> stopsByIndex;
    private final double[][] durationMinutes; // may be null (OSRM unavailable) — always fall back then

    static TravelTimeMatrix build(GeocodeResult depot, List<Stop> stops, RoutingService routingService, double avgSpeedKmh) {
        List<GeocodeResult> points = new ArrayList<>();
        points.add(depot);
        for (Stop stop : stops) {
            points.add(new GeocodeResult(stop.getLat(), stop.getLon()));
        }
        double[][] matrix = routingService.fetchDurationMatrixMinutes(points);

        Map<Long, Integer> indexByStopId = new HashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            indexByStopId.put(stops.get(i).getId(), i + 1); // +1: depot occupies index 0
        }
        return new TravelTimeMatrix(depot, avgSpeedKmh, indexByStopId, stops, matrix);
    }

    private TravelTimeMatrix(GeocodeResult depot, double avgSpeedKmh, Map<Long, Integer> indexByStopId,
                              List<Stop> stopsByIndex, double[][] durationMinutes) {
        this.depot = depot;
        this.avgSpeedKmh = avgSpeedKmh;
        this.indexByStopId = indexByStopId;
        this.stopsByIndex = stopsByIndex;
        this.durationMinutes = durationMinutes;
    }

    double fromDepot(Stop to) {
        return minutesBetween(0, indexByStopId.get(to.getId()), depot.lat(), depot.lon(), to.getLat(), to.getLon());
    }

    double between(Stop from, Stop to) {
        return minutesBetween(indexByStopId.get(from.getId()), indexByStopId.get(to.getId()),
            from.getLat(), from.getLon(), to.getLat(), to.getLon());
    }

    private double minutesBetween(int fromIndex, int toIndex, double fromLat, double fromLon, double toLat, double toLon) {
        if (durationMinutes != null) {
            double minutes = durationMinutes[fromIndex][toIndex];
            if (!Double.isNaN(minutes)) {
                return minutes;
            }
        }
        return DistanceUtil.kmBetween(fromLat, fromLon, toLat, toLon) / avgSpeedKmh * 60;
    }
}
