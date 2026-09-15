package dk.madpakke.domain;

import java.util.ArrayList;
import java.util.List;

public class Route {

    private Long id;
    private String routeDate;
    private Long driverId;
    private String driverName;
    private double estimatedMinutes;
    private int sequenceIndex;
    private List<Stop> stops = new ArrayList<>();

    // Not persisted — computed on the fly from the depot address whenever a route is
    // returned by the API, so it always reflects the current depot setting.
    private String googleMapsUrl;

    // How many of this route's stops are NOT covered by googleMapsUrl (Google Maps'
    // free directions link caps at 10 locations total including the start point).
    private int googleMapsExcludedStopCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRouteDate() {
        return routeDate;
    }

    public void setRouteDate(String routeDate) {
        this.routeDate = routeDate;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getDriverName() {
        return driverName;
    }

    public void setDriverName(String driverName) {
        this.driverName = driverName;
    }

    public double getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public void setEstimatedMinutes(double estimatedMinutes) {
        this.estimatedMinutes = estimatedMinutes;
    }

    public int getSequenceIndex() {
        return sequenceIndex;
    }

    public void setSequenceIndex(int sequenceIndex) {
        this.sequenceIndex = sequenceIndex;
    }

    public List<Stop> getStops() {
        return stops;
    }

    public void setStops(List<Stop> stops) {
        this.stops = stops;
    }

    public String getGoogleMapsUrl() {
        return googleMapsUrl;
    }

    public void setGoogleMapsUrl(String googleMapsUrl) {
        this.googleMapsUrl = googleMapsUrl;
    }

    public int getGoogleMapsExcludedStopCount() {
        return googleMapsExcludedStopCount;
    }

    public void setGoogleMapsExcludedStopCount(int googleMapsExcludedStopCount) {
        this.googleMapsExcludedStopCount = googleMapsExcludedStopCount;
    }
}
