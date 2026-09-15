package dk.madpakke.repository;

import dk.madpakke.domain.MenuLocation;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MenuLocationRepository {

    private final JdbcTemplate jdbc;

    public MenuLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MenuLocation> MAPPER = (rs, i) -> new MenuLocation(
        rs.getLong("id"), rs.getString("name"), rs.getString("mobile_pay_number"), rs.getInt("sort_order"));

    public List<MenuLocation> findAll() {
        return jdbc.query("SELECT * FROM menu_location ORDER BY sort_order, id", MAPPER);
    }

    public Optional<MenuLocation> findById(long id) {
        return jdbc.query("SELECT * FROM menu_location WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM menu_location", Integer.class);
        return c == null ? 0 : c;
    }

    public MenuLocation insert(String name, String mobilePayNumber, int sortOrder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO menu_location (name, mobile_pay_number, sort_order) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, mobilePayNumber);
            ps.setInt(3, sortOrder);
            return ps;
        }, keyHolder);
        return new MenuLocation(keyHolder.getKey().longValue(), name, mobilePayNumber, sortOrder);
    }

    public void update(long id, String name, String mobilePayNumber) {
        jdbc.update("UPDATE menu_location SET name = ?, mobile_pay_number = ? WHERE id = ?",
            name, mobilePayNumber, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM menu_location WHERE id = ?", id);
    }
}
