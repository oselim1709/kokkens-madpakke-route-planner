package dk.madpakke.domain;

public class MenuLocation {

    private Long id;
    private String name;
    private String mobilePayNumber;
    private int sortOrder;

    public MenuLocation() {
    }

    public MenuLocation(Long id, String name, String mobilePayNumber, int sortOrder) {
        this.id = id;
        this.name = name;
        this.mobilePayNumber = mobilePayNumber;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMobilePayNumber() {
        return mobilePayNumber;
    }

    public void setMobilePayNumber(String mobilePayNumber) {
        this.mobilePayNumber = mobilePayNumber;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
