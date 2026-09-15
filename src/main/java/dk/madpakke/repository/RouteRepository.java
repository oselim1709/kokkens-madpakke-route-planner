package dk.madpakke.repository;

import dk.madpakke.domain.Route;
import dk.madpakke.domain.Stop;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RouteRepository {

    private final JdbcTemplate jdbc;
    private final StopRepository stopRepository;

    public RouteRepository(JdbcTemplate jdbc, StopRepository stopRepository) {
        this.jdbc = jdbc;
        this.stopRepository = stopRepository;
    }

    @Transactional
    public void deleteAll() {
        jdbc.update("DELETE FROM route_stop");
        jdbc.update("DELETE FROM route");
    }

    @Transactional
    public List<Route> replaceRoutesForDate(String date, List<Route> newRoutes) {
        List<Long> oldRouteIds = jdbc.queryForList(
            "SELECT id FROM route WHERE route_date = ?", Long.class, date);
        for (Long routeId : oldRouteIds) {
            jdbc.update("DELETE FROM route_stop WHERE route_id = ?", routeId);
        }
        jdbc.update("DELETE FROM route WHERE route_date = ?", date);

        for (Route route : newRoutes) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO route (route_date, driver_id, estimated_minutes, sequence_index)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, route.getRouteDate());
                if (route.getDriverId() != null) {
                    ps.setLong(2, route.getDriverId());
                } else {
                    ps.setNull(2, java.sql.Types.INTEGER);
                }
                ps.setDouble(3, route.getEstimatedMinutes());
                ps.setInt(4, route.getSequenceIndex());
                return ps;
            }, keyHolder);
            long routeId = keyHolder.getKey().longValue();
            route.setId(routeId);

            int order = 0;
            for (Stop stop : route.getStops()) {
                jdbc.update("INSERT INTO route_stop (route_id, stop_id, stop_order) VALUES (?, ?, ?)",
                    routeId, stop.getId(), order++);
            }
        }
        return newRoutes;
    }

    public List<Route> findByDate(String date) {
        List<Route> routes = jdbc.query("""
            SELECT r.id, r.route_date, r.driver_id, r.estimated_minutes, r.sequence_index, d.name AS driver_name
            FROM route r LEFT JOIN driver d ON d.id = r.driver_id
            WHERE r.route_date = ?
            ORDER BY r.sequence_index
            """, this::mapRouteRow, date);
        attachStops(routes);
        return routes;
    }

    public Optional<Route> findById(long id) {
        List<Route> routes = jdbc.query("""
            SELECT r.id, r.route_date, r.driver_id, r.estimated_minutes, r.sequence_index, d.name AS driver_name
            FROM route r LEFT JOIN driver d ON d.id = r.driver_id
            WHERE r.id = ?
            """, this::mapRouteRow, id);
        attachStops(routes);
        return routes.stream().findFirst();
    }

    public void assignDriver(long routeId, Long driverId) {
        if (driverId == null) {
            jdbc.update("UPDATE route SET driver_id = NULL WHERE id = ?", routeId);
        } else {
            jdbc.update("UPDATE route SET driver_id = ? WHERE id = ?", driverId, routeId);
        }
    }

    private Route mapRouteRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Route r = new Route();
        r.setId(rs.getLong("id"));
        r.setRouteDate(rs.getString("route_date"));
        long driverId = rs.getLong("driver_id");
        r.setDriverId(rs.wasNull() ? null : driverId);
        r.setDriverName(rs.getString("driver_name"));
        r.setEstimatedMinutes(rs.getDouble("estimated_minutes"));
        r.setSequenceIndex(rs.getInt("sequence_index"));
        return r;
    }

    private void attachStops(List<Route> routes) {
        if (routes.isEmpty()) {
            return;
        }
        Map<Long, Route> byId = new LinkedHashMap<>();
        for (Route r : routes) {
            byId.put(r.getId(), r);
        }
        String placeholders = String.join(",", routes.stream().map(r -> "?").toList());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT route_id, stop_id FROM route_stop WHERE route_id IN (" + placeholders + ") ORDER BY route_id, stop_order",
            routes.stream().map(Route::getId).toArray());

        List<Long> allStopIds = rows.stream().map(row -> ((Number) row.get("stop_id")).longValue()).distinct().toList();
        Map<Long, Stop> stopsById = new LinkedHashMap<>();
        for (Stop s : stopRepository.findByIds(allStopIds)) {
            stopsById.put(s.getId(), s);
        }

        for (Map<String, Object> row : rows) {
            long routeId = ((Number) row.get("route_id")).longValue();
            long stopId = ((Number) row.get("stop_id")).longValue();
            Route route = byId.get(routeId);
            Stop stop = stopsById.get(stopId);
            if (route != null && stop != null) {
                route.getStops().add(stop);
            }
        }
    }
}
