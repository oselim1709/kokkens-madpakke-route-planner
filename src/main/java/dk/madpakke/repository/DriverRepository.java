package dk.madpakke.repository;

import dk.madpakke.domain.Driver;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DriverRepository {

    private final JdbcTemplate jdbc;

    public DriverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Driver> findAll() {
        return jdbc.query("SELECT * FROM driver ORDER BY name",
            (rs, i) -> new Driver(rs.getLong("id"), rs.getString("name")));
    }

    public Driver insert(String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO driver (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            return ps;
        }, keyHolder);
        return new Driver(keyHolder.getKey().longValue(), name);
    }

    public void update(long id, String name) {
        jdbc.update("UPDATE driver SET name = ? WHERE id = ?", name, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM driver WHERE id = ?", id);
    }
}
