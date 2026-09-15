package dk.madpakke.domain;

/** The one dish that changes on the menu card each week — everything else on the card is fixed. */
public class WeeklyDish {

    private String name;
    private String subtitle;
    private String description;
    private String price;
    private String proteinGrams;
    private String kcal;
    private String allergens;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getProteinGrams() {
        return proteinGrams;
    }

    public void setProteinGrams(String proteinGrams) {
        this.proteinGrams = proteinGrams;
    }

    public String getKcal() {
        return kcal;
    }

    public void setKcal(String kcal) {
        this.kcal = kcal;
    }

    public String getAllergens() {
        return allergens;
    }

    public void setAllergens(String allergens) {
        this.allergens = allergens;
    }
}
