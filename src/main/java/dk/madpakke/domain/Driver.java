package dk.madpakke.domain;

public class Driver {

    private Long id;
    private String name;
    private boolean active = true;
    private String endAddress;
    private Double endLat;
    private Double endLon;

    public Driver() {
    }

    public Driver(Long id, String name) {
        this(id, name, true);
    }

    public Driver(Long id, String name, boolean active) {
        this.id = id;
        this.name = name;
        this.active = active;
    }

    public String getEndAddress() {
        return endAddress;
    }

    public void setEndAddress(String endAddress) {
        this.endAddress = endAddress;
    }

    public Double getEndLat() {
        return endLat;
    }

    public void setEndLat(Double endLat) {
        this.endLat = endLat;
    }

    public Double getEndLon() {
        return endLon;
    }

    public void setEndLon(Double endLon) {
        this.endLon = endLon;
    }

    /** True when the end address has coordinates, i.e. it can be used for driving-time estimates. */
    public boolean isEndGeocoded() {
        return endLat != null && endLon != null;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
}
