package dk.madpakke.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dk.madpakke.domain.Stop;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoogleMapsUrlBuilderTest {

    private static List<Stop> stops(int n) {
        List<Stop> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Stop s = new Stop();
            s.setAddress("Gade " + i + ", 2000 By");
            list.add(s);
        }
        return list;
    }

    private static String decoded(String url) {
        return URLDecoder.decode(url, StandardCharsets.UTF_8);
    }

    @Test
    void withoutEndAddressLastStopIsDestination() {
        String url = decoded(GoogleMapsUrlBuilder.build("Start 1", stops(3)));
        assertTrue(url.contains("origin=Start 1"));
        assertTrue(url.contains("destination=Gade 3, 2000 By"));
        assertTrue(url.contains("waypoints=Gade 1, 2000 By|Gade 2, 2000 By"));
    }

    @Test
    void withEndAddressItIsDestinationAndAllStopsAreWaypoints() {
        String url = decoded(GoogleMapsUrlBuilder.build("Start 1", stops(3), "Slut 9, 2635 Ishøj"));
        assertTrue(url.contains("destination=Slut 9, 2635 Ishøj"));
        assertTrue(url.contains("waypoints=Gade 1, 2000 By|Gade 2, 2000 By|Gade 3, 2000 By"));
    }

    @Test
    void singleStopWithoutEndHasNoWaypoints() {
        String url = decoded(GoogleMapsUrlBuilder.build("Start 1", stops(1)));
        assertFalse(url.contains("waypoints="));
        assertTrue(url.contains("destination=Gade 1, 2000 By"));
    }

    @Test
    void neverExceedsTenLocationsIncludingOriginAndEnd() {
        // origin + 9 stops = 10, or origin + 8 stops + end address = 10
        assertEquals(0, GoogleMapsUrlBuilder.excludedStopCount(stops(9)));
        assertEquals(1, GoogleMapsUrlBuilder.excludedStopCount(stops(10)));
        assertEquals(0, GoogleMapsUrlBuilder.excludedStopCount(stops(8), "Slut"));
        assertEquals(1, GoogleMapsUrlBuilder.excludedStopCount(stops(9), "Slut"));

        String withEnd = decoded(GoogleMapsUrlBuilder.build("Start", stops(17), "Slut"));
        int waypoints = withEnd.split("waypoints=")[1].split("\\|").length;
        assertEquals(8, waypoints);
        String withoutEnd = decoded(GoogleMapsUrlBuilder.build("Start", stops(17)));
        // 8 waypoints + the 9th stop as destination
        assertEquals(8, withoutEnd.split("waypoints=")[1].split("\\|").length);
        assertTrue(withoutEnd.contains("destination=Gade 9, 2000 By"));
    }

    @Test
    void waypointSeparatorIsPercentEncoded() {
        String raw = GoogleMapsUrlBuilder.build("Start", stops(3), "Slut");
        assertTrue(raw.contains("%7C"));
        assertFalse(raw.contains("|"));
    }

    @Test
    void returnsNullWithoutStopsOrOrigin() {
        assertNull(GoogleMapsUrlBuilder.build("Start", List.of()));
        assertNull(GoogleMapsUrlBuilder.build("", stops(2)));
        assertNull(GoogleMapsUrlBuilder.build(null, stops(2)));
    }
}
