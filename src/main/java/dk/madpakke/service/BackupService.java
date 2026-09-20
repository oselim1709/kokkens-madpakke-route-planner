package dk.madpakke.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Consistent copies of the live SQLite database, using VACUUM INTO (safe while the app is
 * running, unlike copying the file). Copies live in a "backups" folder next to the database,
 * i.e. on the same persistent disk, so they protect against mistakes (a wrongly used reset
 * button, bad data) — not against losing the disk itself. For that, the user can download a
 * copy to their own device.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int KEEP_AUTO = 8;
    private static final int KEEP_MANUAL = 5;
    private static final Duration AUTO_MAX_AGE = Duration.ofDays(7);

    public record BackupInfo(String name, String kind, long sizeBytes, String createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final Path backupDir;

    public BackupService(JdbcTemplate jdbc, @Value("${DB_PATH:data/madpakke.db}") String dbPath) {
        this.jdbc = jdbc;
        this.backupDir = Path.of(dbPath).toAbsolutePath().getParent().resolve("backups");
    }

    /** @param kind "auto" (scheduled) or "manual" (button) — each kind is pruned separately. */
    public synchronized Path createBackup(String kind) throws IOException {
        Files.createDirectories(backupDir);
        Path target = backupDir.resolve(kind + "-" + LocalDateTime.now().format(STAMP) + ".db");
        Files.deleteIfExists(target);
        writeSnapshot(target);
        prune(kind, "auto".equals(kind) ? KEEP_AUTO : KEEP_MANUAL);
        log.info("Sikkerhedskopi oprettet: {}", target.getFileName());
        return target;
    }

    /** Writes a consistent copy of the live database to target (which must not exist or be empty). */
    public void writeSnapshot(Path target) {
        jdbc.update("VACUUM INTO ?", target.toString());
    }

    public List<BackupInfo> list() {
        File[] files = backupDir.toFile().listFiles((dir, name) -> name.endsWith(".db"));
        if (files == null) {
            return List.of();
        }
        List<BackupInfo> result = new ArrayList<>();
        for (File f : files) {
            String kind = f.getName().startsWith("manual-") ? "manual" : "auto";
            result.add(new BackupInfo(f.getName(), kind, f.length(),
                Instant.ofEpochMilli(f.lastModified()).toString()));
        }
        result.sort(Comparator.comparing(BackupInfo::createdAt).reversed());
        return result;
    }

    @Scheduled(cron = "0 0 3 * * SUN", zone = "Europe/Copenhagen")
    public void weeklyBackup() {
        try {
            createBackup("auto");
        } catch (Exception e) {
            log.error("Ugentlig sikkerhedskopi fejlede", e);
        }
    }

    /** Covers a missed Sunday-night run (e.g. the server was down): back up on startup if the newest auto copy is stale. */
    @EventListener(ApplicationReadyEvent.class)
    public void backupOnStartupIfStale() {
        try {
            Instant newestAuto = list().stream()
                .filter(b -> "auto".equals(b.kind()))
                .map(b -> Instant.parse(b.createdAt()))
                .findFirst().orElse(null);
            if (newestAuto == null || Duration.between(newestAuto, Instant.now()).compareTo(AUTO_MAX_AGE) > 0) {
                createBackup("auto");
            }
        } catch (Exception e) {
            log.error("Sikkerhedskopi ved opstart fejlede", e);
        }
    }

    private void prune(String kind, int keep) {
        File[] files = backupDir.toFile().listFiles((dir, name) -> name.startsWith(kind + "-") && name.endsWith(".db"));
        if (files == null || files.length <= keep) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (int i = keep; i < files.length; i++) {
            if (!files[i].delete()) {
                log.warn("Kunne ikke slette gammel sikkerhedskopi {}", files[i].getName());
            }
        }
    }
}
