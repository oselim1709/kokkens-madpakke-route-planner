package dk.madpakke;

import java.io.File;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MadpakkeApplication {

    public static void main(String[] args) {
        String dbPath = System.getenv().getOrDefault("DB_PATH", "data/madpakke.db");
        File parentDir = new File(dbPath).getAbsoluteFile().getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        SpringApplication.run(MadpakkeApplication.class, args);
    }
}
