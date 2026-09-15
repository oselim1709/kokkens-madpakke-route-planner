package dk.madpakke.service;

import dk.madpakke.domain.Stop;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds a Google Maps turn-by-turn navigation link for a route: depot → each stop in
 * the planned order → last stop. Waypoint order is fixed (no "optimize:true"), since the
 * order already reflects deadlines and the balanced route split — Google should not reorder it.
 *
 * Locations are passed as address TEXT rather than "lat,lng" coordinates — a bare
 * coordinate shows up in Google Maps as an unlabeled "Markeret" (dropped pin), which
 * leaves the driver unable to tell where a stop actually is without cross-referencing the
 * app separately. This relies on the stored address text being accurate enough for Google
 * to re-geocode correctly, which is why stop addresses get corrected (not just their
 * coordinates) whenever geocoding needed a relaxed/city-corrected match.
 *
 * Google Maps' free multi-stop directions link tops out at 10 locations total (the start
 * point plus waypoints plus destination). A route with more stops than that gets a link
 * covering only the first {@link #MAX_STOPS_IN_LINK} stops — {@link #excludedStopCount}
 * tells the caller how many were left out, so they can flag it for the driver.
 */
public final class GoogleMapsUrlBuilder {

    /** Google Maps caps a directions link at 10 locations total, including the start point. */
    public static final int MAX_LOCATIONS_INCLUDING_ORIGIN = 10;
    public static final int MAX_STOPS_IN_LINK = MAX_LOCATIONS_INCLUDING_ORIGIN - 1;

    private GoogleMapsUrlBuilder() {
    }

    public static String build(String originAddress, List<Stop> orderedStops) {
        if (orderedStops == null || orderedStops.isEmpty() || originAddress == null || originAddress.isBlank()) {
            return null;
        }
        List<Stop> included = orderedStops.size() > MAX_STOPS_IN_LINK
            ? orderedStops.subList(0, MAX_STOPS_IN_LINK)
            : orderedStops;

        StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving");
        url.append("&origin=").append(encode(originAddress));

        Stop last = included.get(included.size() - 1);
        url.append("&destination=").append(encode(last.getAddress()));

        if (included.size() > 1) {
            // Build the raw "address|address|..." string first, then percent-encode it as one
            // unit — a bare "|" is not a safe URL character, and while browsers usually tolerate
            // it when a link is clicked directly, an app that re-parses a pasted/shared copy of
            // the URL (as opposed to following an already-parsed link object) can mishandle it,
            // e.g. truncating at the first "|" and silently falling back to some default
            // destination. Encoding it as %7C is unambiguous everywhere.
            StringBuilder rawWaypoints = new StringBuilder();
            for (int i = 0; i < included.size() - 1; i++) {
                if (i > 0) rawWaypoints.append("|");
                rawWaypoints.append(included.get(i).getAddress());
            }
            url.append("&waypoints=").append(URLEncoder.encode(rawWaypoints.toString(), StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    /** How many of the route's stops are NOT covered by the link (0 if it covers all of them). */
    public static int excludedStopCount(List<Stop> orderedStops) {
        if (orderedStops == null) {
            return 0;
        }
        return Math.max(0, orderedStops.size() - MAX_STOPS_IN_LINK);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
