package dk.madpakke.web;

import dk.madpakke.domain.MenuLocation;
import dk.madpakke.domain.WeeklyDish;
import dk.madpakke.repository.MenuLocationRepository;
import dk.madpakke.service.MenuPdfService;
import dk.madpakke.service.WeeklyDishService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final WeeklyDishService weeklyDishService;
    private final MenuLocationRepository locationRepository;
    private final MenuPdfService menuPdfService;

    public MenuController(WeeklyDishService weeklyDishService,
                           MenuLocationRepository locationRepository,
                           MenuPdfService menuPdfService) {
        this.weeklyDishService = weeklyDishService;
        this.locationRepository = locationRepository;
        this.menuPdfService = menuPdfService;
    }

    @GetMapping("/dish")
    public WeeklyDish getDish() {
        return weeklyDishService.get();
    }

    @PutMapping("/dish")
    public WeeklyDish saveDish(@RequestBody WeeklyDish dish) {
        weeklyDishService.save(dish);
        return weeklyDishService.get();
    }

    @GetMapping("/locations")
    public List<MenuLocation> locations() {
        return locationRepository.findAll();
    }

    @PostMapping("/locations")
    public MenuLocation addLocation(@RequestBody Map<String, String> body) {
        int nextOrder = locationRepository.count();
        return locationRepository.insert(body.get("name"), body.get("mobilePayNumber"), nextOrder);
    }

    @PutMapping("/locations/{id}")
    public ResponseEntity<Void> updateLocation(@PathVariable long id, @RequestBody Map<String, String> body) {
        locationRepository.update(id, body.get("name"), body.get("mobilePayNumber"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/locations/{id}")
    public ResponseEntity<Void> deleteLocation(@PathVariable long id) {
        locationRepository.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/pdf/{locationId}", produces = "application/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long locationId) throws IOException {
        return locationRepository.findById(locationId)
            .map(this::renderPdfResponse)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/pdf/all.zip", produces = "application/zip")
    public ResponseEntity<byte[]> allPdfsZip() throws IOException {
        WeeklyDish dish = weeklyDishService.get();
        List<MenuLocation> locations = locationRepository.findAll();

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            for (MenuLocation location : locations) {
                byte[] pdf = menuPdfService.render(location, dish);
                zip.putNextEntry(new ZipEntry(fileName(location)));
                zip.write(pdf);
                zip.closeEntry();
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename("menukort.zip", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).contentType(MediaType.valueOf("application/zip")).body(zipBytes.toByteArray());
    }

    private ResponseEntity<byte[]> renderPdfResponse(MenuLocation location) {
        try {
            WeeklyDish dish = weeklyDishService.get();
            byte[] pdf = menuPdfService.render(location, dish);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.inline().filename(fileName(location), StandardCharsets.UTF_8).build());
            return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_PDF).body(pdf);
        } catch (IOException e) {
            throw new RuntimeException("Kunne ikke generere PDF for " + location.getName(), e);
        }
    }

    private String fileName(MenuLocation location) {
        String safe = location.getName().replaceAll("[^a-zA-Z0-9æøåÆØÅ ]", "").replace(' ', '_');
        return "menukort_" + safe + ".pdf";
    }
}
