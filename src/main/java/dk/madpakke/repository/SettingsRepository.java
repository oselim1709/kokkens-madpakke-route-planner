package dk.madpakke.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        List<String> values = jdbc.query("SELECT value FROM settings WHERE key = ?",
            (rs, i) -> rs.getString("value"), key);
        return values.stream().findFirst();
    }

    public void set(String key, String value) {
        int updated = jdbc.update("UPDATE settings SET value = ? WHERE key = ?", value, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO settings (key, value) VALUES (?, ?)", key, value);
        }
    }
}
