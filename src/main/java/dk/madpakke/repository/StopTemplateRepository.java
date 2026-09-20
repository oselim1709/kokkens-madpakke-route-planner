package dk.madpakke.repository;

import dk.madpakke.domain.Stop;
import dk.madpakke.domain.StopTemplate;
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
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StopTemplateRepository {

    private final JdbcTemplate jdbc;

    public StopTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Stop> ITEM_MAPPER = StopTemplateRepository::mapItemRow;

    private static Stop mapItemRow(ResultSet rs, int rowNum) throws SQLException {
        Stop s = new Stop();
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
        s.setFloorDoor(rs.getString("floor_door"));
        long preferredDriverId = rs.getLong("preferred_driver_id");
        s.setPreferredDriverId(rs.wasNull() ? null : preferredDriverId);
        s.setActive(true);
        return s;
    }

    /** List view — template metadata and item count, without loading every item's full data. */
    public List<StopTemplate> findAllSummaries() {
        return jdbc.query("""
            SELECT t.id, t.name, t.created_at, COUNT(i.id) AS item_count
            FROM stop_template t
            LEFT JOIN stop_template_item i ON i.template_id = t.id
            GROUP BY t.id, t.name, t.created_at
            ORDER BY t.created_at DESC, t.id DESC
            """, (rs, rowNum) -> {
            StopTemplate t = new StopTemplate();
            t.setId(rs.getLong("id"));
            t.setName(rs.getString("name"));
            t.setCreatedAt(rs.getString("created_at"));
            t.setItemCount(rs.getInt("item_count"));
            return t;
        });
    }

    public Optional<StopTemplate> findById(long id) {
        List<StopTemplate> result = jdbc.query(
            "SELECT id, name, created_at FROM stop_template WHERE id = ?",
            (rs, rowNum) -> {
                StopTemplate t = new StopTemplate();
                t.setId(rs.getLong("id"));
                t.setName(rs.getString("name"));
                t.setCreatedAt(rs.getString("created_at"));
                return t;
            }, id);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        StopTemplate template = result.get(0);
        template.setItems(jdbc.query(
            "SELECT * FROM stop_template_item WHERE template_id = ? ORDER BY id", ITEM_MAPPER, id));
        template.setItemCount(template.getItems().size());
        return Optional.of(template);
    }

    @Transactional
    public StopTemplate insert(String name, List<Stop> items) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO stop_template (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            return ps;
        }, keyHolder);
        long templateId = keyHolder.getKey().longValue();

        for (Stop item : items) {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO stop_template_item (template_id, customer_name, address, lat, lon, stop_type,
                        deadline, qty_normal_lunchbox, qty_fitness_lunchbox, qty_musli_bar, qty_fruit,
                        qty_risengroed, qty_sandwich, qty_cake, special_order, preferred_driver_id, floor_door)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
                ps.setLong(1, templateId);
                ps.setString(2, item.getCustomerName());
                ps.setString(3, item.getAddress());
                if (item.getLat() != null) {
                    ps.setDouble(4, item.getLat());
                } else {
                    ps.setNull(4, Types.REAL);
                }
                if (item.getLon() != null) {
                    ps.setDouble(5, item.getLon());
                } else {
                    ps.setNull(5, Types.REAL);
                }
                ps.setString(6, item.getStopType().name());
                ps.setString(7, item.getDeadline() == null ? null : item.getDeadline().toString());
                ps.setInt(8, item.getQtyNormalLunchbox());
                ps.setInt(9, item.getQtyFitnessLunchbox());
                ps.setInt(10, item.getQtyMusliBar());
                ps.setInt(11, item.getQtyFruit());
                ps.setInt(12, item.getQtyRisengroed());
                ps.setInt(13, item.getQtySandwich());
                ps.setInt(14, item.getQtyCake());
                ps.setString(15, item.getSpecialOrder());
                if (item.getPreferredDriverId() != null) {
                    ps.setLong(16, item.getPreferredDriverId());
                } else {
                    ps.setNull(16, Types.INTEGER);
                }
                ps.setString(17, item.getFloorDoor());
                return ps;
            });
        }

        StopTemplate result = new StopTemplate();
        result.setId(templateId);
        result.setName(name);
        result.setItems(items);
        result.setItemCount(items.size());
        return result;
    }

    @Transactional
    public void delete(long id) {
        jdbc.update("DELETE FROM stop_template_item WHERE template_id = ?", id);
        jdbc.update("DELETE FROM stop_template WHERE id = ?", id);
    }
}
