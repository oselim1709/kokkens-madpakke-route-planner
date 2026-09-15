package dk.madpakke.repository;

import dk.madpakke.domain.Stop;
import dk.madpakke.domain.StopType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class StopRepository {

    private final JdbcTemplate jdbc;

    public StopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Stop> MAPPER = StopRepository::mapRow;

    private static Stop mapRow(ResultSet rs, int rowNum) throws SQLException {
        Stop s = new Stop();
        s.setId(rs.getLong("id"));
        s.setCustomerName(rs.getString("customer_name"));
        s.setAddress(rs.getString("address"));
        double lat = rs.getDouble("lat");
        s.setLat(rs.wasNull() ? null : lat);
        double lon = rs.getDouble("lon");
        s.setLon(rs.wasNull() ? null : lon);
        s.setStopType(StopType.valueOf(rs.getString("stop_type")));
        String deadline = rs.getString("deadline");
        s.setDeadline(deadline == null || deadline.isBlank() ? null : LocalTime.parse(deadline));
        s.setQtyNormalLunchbox(rs.getInt("qty_normal_lunchbox"));
        s.setQtyFitnessLunchbox(rs.getInt("qty_fitness_lunchbox"));
        s.setQtyMusliBar(rs.getInt("qty_musli_bar"));
        s.setQtyFruit(rs.getInt("qty_fruit"));
        s.setQtyRisengroed(rs.getInt("qty_risengroed"));
        s.setQtySandwich(rs.getInt("qty_sandwich"));
        s.setQtyCake(rs.getInt("qty_cake"));
        s.setSpecialOrder(rs.getString("special_order"));
        s.setActive(rs.getInt("active") == 1);
        s.setCreatedAt(rs.getString("created_at"));
        long preferredDriverId = rs.getLong("preferred_driver_id");
        s.setPreferredDriverId(rs.wasNull() ? null : preferredDriverId);
        return s;
    }

    public List<Stop> findAll() {
        return jdbc.query("SELECT * FROM stop ORDER BY created_at DESC, id DESC", MAPPER);
    }

    public List<Stop> findActive() {
        return jdbc.query("SELECT * FROM stop WHERE active = 1 ORDER BY id", MAPPER);
    }

    public Optional<Stop> findById(long id) {
        List<Stop> result = jdbc.query("SELECT * FROM stop WHERE id = ?", MAPPER, id);
        return result.stream().findFirst();
    }

    public List<Stop> findByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        return jdbc.query("SELECT * FROM stop WHERE id IN (" + placeholders + ")", MAPPER, ids.toArray());
    }

    public Stop insert(Stop s) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                INSERT INTO stop (customer_name, address, lat, lon, stop_type, deadline,
                    qty_normal_lunchbox, qty_fitness_lunchbox, qty_musli_bar, qty_fruit,
                    qty_risengroed, qty_sandwich, qty_cake, special_order, active, preferred_driver_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            bind(ps, s);
            return ps;
        }, keyHolder);
        s.setId(keyHolder.getKey().longValue());
        return s;
    }

    public void update(Stop s) {
        jdbc.update("""
            UPDATE stop SET customer_name = ?, address = ?, lat = ?, lon = ?, stop_type = ?, deadline = ?,
                qty_normal_lunchbox = ?, qty_fitness_lunchbox = ?, qty_musli_bar = ?, qty_fruit = ?,
                qty_risengroed = ?, qty_sandwich = ?, qty_cake = ?, special_order = ?, active = ?,
                preferred_driver_id = ?
            WHERE id = ?
            """,
            ps -> {
                bind(ps, s);
                ps.setLong(17, s.getId());
            });
    }

    public void updateGeocode(long id, double lat, double lon) {
        jdbc.update("UPDATE stop SET lat = ?, lon = ? WHERE id = ?", lat, lon, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM stop WHERE id = ?", id);
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM stop");
    }

    private void bind(PreparedStatement ps, Stop s) throws SQLException {
        ps.setString(1, s.getCustomerName());
        ps.setString(2, s.getAddress());
        if (s.getLat() != null) {
            ps.setDouble(3, s.getLat());
        } else {
            ps.setNull(3, Types.REAL);
        }
        if (s.getLon() != null) {
            ps.setDouble(4, s.getLon());
        } else {
            ps.setNull(4, Types.REAL);
        }
        ps.setString(5, s.getStopType().name());
        ps.setString(6, s.getDeadline() == null ? null : s.getDeadline().toString());
        ps.setInt(7, s.getQtyNormalLunchbox());
        ps.setInt(8, s.getQtyFitnessLunchbox());
        ps.setInt(9, s.getQtyMusliBar());
        ps.setInt(10, s.getQtyFruit());
        ps.setInt(11, s.getQtyRisengroed());
        ps.setInt(12, s.getQtySandwich());
        ps.setInt(13, s.getQtyCake());
        ps.setString(14, s.getSpecialOrder());
        ps.setInt(15, s.isActive() ? 1 : 0);
        if (s.getPreferredDriverId() != null) {
            ps.setLong(16, s.getPreferredDriverId());
        } else {
            ps.setNull(16, Types.INTEGER);
        }
    }
}
