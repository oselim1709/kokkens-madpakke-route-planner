package dk.madpakke.service;

import dk.madpakke.domain.WeeklyDish;
import dk.madpakke.repository.MenuLocationRepository;
import dk.madpakke.repository.SettingsRepository;
import org.springframework.stereotype.Service;

/**
 * Holds the one dish on the menu card that changes week to week, plus seeds the four
 * fixed pickup locations on first run. Both are simple enough to not need their own tables
 * beyond the settings key/value store (dish) and a small locations table.
 */
@Service
public class WeeklyDishService {

    private static final String KEY_NAME = "dish_name";
    private static final String KEY_SUBTITLE = "dish_subtitle";
    private static final String KEY_DESCRIPTION = "dish_description";
    private static final String KEY_PRICE = "dish_price";
    private static final String KEY_PROTEIN = "dish_protein";
    private static final String KEY_KCAL = "dish_kcal";
    private static final String KEY_ALLERGENS = "dish_allergens";

    private final SettingsRepository settingsRepository;

    public WeeklyDishService(SettingsRepository settingsRepository, MenuLocationRepository locationRepository) {
        this.settingsRepository = settingsRepository;
        if (settingsRepository.get(KEY_NAME).isEmpty()) {
            seedDefaultDish();
        }
        if (locationRepository.count() == 0) {
            seedDefaultLocations(locationRepository);
        }
    }

    private void seedDefaultDish() {
        WeeklyDish dish = new WeeklyDish();
        dish.setName("Kokkensmadpakke");
        dish.setSubtitle("Trænings madpakken");
        dish.setDescription("Kokkens kyllingepopcorn serveres med bambusdampet ris vendt med snittet grønt, "
            + "broccoli samt græsk yoghurt med 4 slags krydderurter, hertil sesam");
        dish.setPrice("69");
        dish.setProteinGrams("52,44");
        dish.setKcal("519");
        dish.setAllergens("gluten, laktose, sesam, soja");
        save(dish);
    }

    private void seedDefaultLocations(MenuLocationRepository locationRepository) {
        locationRepository.insert("Badr Fightclub", "102450", 0);
        locationRepository.insert("Copenhagen Gym", "30266", 1);
        locationRepository.insert("Silverback Søborg", "90376", 2);
        locationRepository.insert("Silverback Herlev", "99346", 3);
    }

    public WeeklyDish get() {
        WeeklyDish dish = new WeeklyDish();
        dish.setName(settingsRepository.get(KEY_NAME).orElse(""));
        dish.setSubtitle(settingsRepository.get(KEY_SUBTITLE).orElse(""));
        dish.setDescription(settingsRepository.get(KEY_DESCRIPTION).orElse(""));
        dish.setPrice(settingsRepository.get(KEY_PRICE).orElse(""));
        dish.setProteinGrams(settingsRepository.get(KEY_PROTEIN).orElse(""));
        dish.setKcal(settingsRepository.get(KEY_KCAL).orElse(""));
        dish.setAllergens(settingsRepository.get(KEY_ALLERGENS).orElse(""));
        return dish;
    }

    public void save(WeeklyDish dish) {
        settingsRepository.set(KEY_NAME, nullToEmpty(dish.getName()));
        settingsRepository.set(KEY_SUBTITLE, nullToEmpty(dish.getSubtitle()));
        settingsRepository.set(KEY_DESCRIPTION, nullToEmpty(dish.getDescription()));
        settingsRepository.set(KEY_PRICE, nullToEmpty(dish.getPrice()));
        settingsRepository.set(KEY_PROTEIN, nullToEmpty(dish.getProteinGrams()));
        settingsRepository.set(KEY_KCAL, nullToEmpty(dish.getKcal()));
        settingsRepository.set(KEY_ALLERGENS, nullToEmpty(dish.getAllergens()));
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
