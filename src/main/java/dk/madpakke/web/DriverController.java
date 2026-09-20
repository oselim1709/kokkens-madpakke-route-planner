package dk.madpakke.web;

import dk.madpakke.domain.Driver;
import dk.madpakke.repository.DriverRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverRepository driverRepository;

    public DriverController(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @GetMapping
    public List<Driver> all() {
        return driverRepository.findAll();
    }

    @PostMapping
    public Driver create(@RequestBody Map<String, String> body) {
        return driverRepository.insert(body.get("name"));
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @RequestBody Map<String, String> body) {
        driverRepository.update(id, body.get("name"));
    }

    @PutMapping("/{id}/active")
    public void setActive(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        driverRepository.setActive(id, Boolean.TRUE.equals(body.get("active")));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        driverRepository.delete(id);
    }
}
