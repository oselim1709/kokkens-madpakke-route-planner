package dk.madpakke.domain;

import java.time.LocalTime;

public class Stop {

    private Long id;
    private String customerName;
    private String address;
    private Double lat;
    private Double lon;
    private StopType stopType;
    private LocalTime deadline;
    private Long preferredDriverId;

    private int qtyNormalLunchbox;
    private int qtyFitnessLunchbox;
    private int qtyMusliBar;
    private int qtyFruit;
    private int qtyRisengroed;
    private int qtySandwich;
    private int qtyCake;
    private String specialOrder;

    private boolean active = true;
    private String createdAt;

    public boolean isGeocoded() {
        return lat != null && lon != null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLon() {
        return lon;
    }

    public void setLon(Double lon) {
        this.lon = lon;
    }

    public StopType getStopType() {
        return stopType;
    }

    public void setStopType(StopType stopType) {
        this.stopType = stopType;
    }

    public LocalTime getDeadline() {
        return deadline;
    }

    public void setDeadline(LocalTime deadline) {
        this.deadline = deadline;
    }

    public Long getPreferredDriverId() {
        return preferredDriverId;
    }

    public void setPreferredDriverId(Long preferredDriverId) {
        this.preferredDriverId = preferredDriverId;
    }

    public int getQtyNormalLunchbox() {
        return qtyNormalLunchbox;
    }

    public void setQtyNormalLunchbox(int qtyNormalLunchbox) {
        this.qtyNormalLunchbox = qtyNormalLunchbox;
    }

    public int getQtyFitnessLunchbox() {
        return qtyFitnessLunchbox;
    }

    public void setQtyFitnessLunchbox(int qtyFitnessLunchbox) {
        this.qtyFitnessLunchbox = qtyFitnessLunchbox;
    }

    public int getQtyMusliBar() {
        return qtyMusliBar;
    }

    public void setQtyMusliBar(int qtyMusliBar) {
        this.qtyMusliBar = qtyMusliBar;
    }

    public int getQtyFruit() {
        return qtyFruit;
    }

    public void setQtyFruit(int qtyFruit) {
        this.qtyFruit = qtyFruit;
    }

    public int getQtyRisengroed() {
        return qtyRisengroed;
    }

    public void setQtyRisengroed(int qtyRisengroed) {
        this.qtyRisengroed = qtyRisengroed;
    }

    public int getQtySandwich() {
        return qtySandwich;
    }

    public void setQtySandwich(int qtySandwich) {
        this.qtySandwich = qtySandwich;
    }

    public int getQtyCake() {
        return qtyCake;
    }

    public void setQtyCake(int qtyCake) {
        this.qtyCake = qtyCake;
    }

    public String getSpecialOrder() {
        return specialOrder;
    }

    public void setSpecialOrder(String specialOrder) {
        this.specialOrder = specialOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
