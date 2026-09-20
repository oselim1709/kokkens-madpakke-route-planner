package dk.madpakke.web;

import dk.madpakke.service.BackupService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    public Map<String, Object> info() {
        return Map.of("backups", backupService.list());
    }

    @PostMapping("/now")
    public Map<String, Object> backupNow() throws IOException {
        backupService.createBackup("manual");
        return info();
    }

    /** A fresh, consistent copy of the whole database as a file, to save on the user's own device. */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download() throws IOException {
        Path temp = Files.createTempFile("madpakke-download-", ".db");
        try {
            backupService.writeSnapshot(temp);
            byte[] data = Files.readAllBytes(temp);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                .filename("kokkens-madpakke-backup-" + LocalDate.now() + ".db").build());
            return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_OCTET_STREAM).body(data);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
