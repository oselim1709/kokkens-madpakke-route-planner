package dk.madpakke.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns a free-text address into coordinates. Tries the Google Maps Geocoding API first
 * (when madpakke.google-maps-api-key is set), and falls back to the free OpenStreetMap
 * Nominatim service otherwise or if the Google lookup fails.
 *
 * Two ways addresses get resolved:
 *  - {@link #search(String, int)}: live suggestions as the user types (used by the address
 *    autocomplete in the UI), so a typo never gets saved in the first place — the user
 *    picks a real, already-geocoded address from a dropdown.
 *  - {@link #geocode(String, GeocodeResult)}: a best-effort lookup for addresses that were
 *    saved without going through the autocomplete (pasted in, dictated from a handwritten
 *    note, etc). Real-world Danish delivery notes are messy — floor/door notation like
 *    "3. tv" or "6. sal til højre" mixed into the address, or a city that doesn't actually
 *    match where the street exists — and Nominatim fails the whole query rather than
 *    ignoring the part it can't match. So this retries with several increasingly relaxed
 *    variants (floor notation stripped, city dropped entirely, house number dropped too),
 *    and — since dropping the city can surface same-named streets anywhere in Denmark —
 *    picks whichever candidate is closest to a reference point (normally the depot) when
 *    one is given.
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);
    private static final double NEARBY_KM = 30;

    // Trailing floor/door notation: ", 3 th", " 2. tv", ", st.", " kl"
    private static final Pattern TRAILING_FLOOR_DOOR = Pattern.compile(
        "(?i)(?:[,\\s]+(?:st|kl|\\d{1,2})\\.?[,\\s]*(?:th|tv|mf)\\.?|[,\\s]+(?:th|tv|mf)\\.?|,\\s*(?:st|kl|\\d{1,2})\\.?|\\s+(?:st|kl)\\.?)$");

    private static final Pattern LEADING_HOUSE_NUMBER = Pattern.compile("^(.*?\\D)\\s*\\d+\\s*[a-zA-Z]?\\s*(,.*)?$");

    // Matches a comma-separated address segment that is Danish floor/door notation rather
    // than part of the actual street address, e.g. "3. tv", "st. th", "1. sal",
    // "6. sal til højre", "st. 001" — Nominatim can't place these and the whole query fails.
    private static final Pattern FLOOR_OR_DOOR_SEGMENT = Pattern.compile(
        "(?i)^(?:st\\.?|\\d{1,2}\\.?)\\s*(?:tv|th|mf)\\.?$"
        + "|^(?:st\\.?|\\d{1,2}\\.?)\\s*sal(?:\\s+til\\s+(?:højre|venstre))?$"
        + "|^stuen$"
        + "|^(?:st\\.?|\\d{1,2}\\.?)\\s*\\d{1,4}$");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String googleApiKey;

    // Nominatim's usage policy caps free use at ~1 request/second.
    private long lastNominatimCallMillis = 0;

    public GeocodingService(@Value("${madpakke.google-maps-api-key:}") String googleApiKey) {
        this.googleApiKey = googleApiKey;
    }

    public boolean usingGoogle() {
        return googleApiKey != null && !googleApiKey.isBlank();
    }

    public List<GeocodeCandidate> search(String query, int limit) {
        return search(query, limit, null);
    }

    /**
     * Live address suggestions for a partial/possibly-misspelled query, for autocomplete.
     *
     * @param near when given, suggestions within ~30 km of this point are listed first (so
     *             "Nørrebrogade 20" means the one in Copenhagen, not Esbjerg).
     */
    public List<GeocodeCandidate> search(String query, int limit, GeocodeResult near) {
        if (query == null || query.isBlank() || query.trim().length() < 3) {
            return List.of();
        }
        try {
            // Denmark's official address register: fuzzy (fixes typos like "Scharlingevej") and
            // free. Falls through to the other providers when it has nothing or is unreachable.
            try {
                List<GeocodeCandidate> dawa = searchWithDawa(query, limit, near);
                if (!dawa.isEmpty()) {
                    return dawa;
                }
            } catch (Exception e) {
                log.warn("DAWA-søgning fejlede for '{}': {}", query, e.getMessage());
            }
            if (usingGoogle()) {
                List<GeocodeCandidate> results = searchWithGoogle(query, limit);
                if (!results.isEmpty()) {
                    return results;
                }
            }
            return searchWithNominatim(query, limit);
        } catch (Exception e) {
            log.warn("Adressesøgning fejlede for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    public Optional<GeocodeResult> geocode(String address) {
        return geocode(address, null);
    }

    /**
     * @param near when given, and a relaxed query returns several same-named streets
     *             across Denmark, the one closest to this point is used.
     */
    public Optional<GeocodeResult> geocode(String address, GeocodeResult near) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        for (String variant : addressVariants(address)) {
            List<GeocodeCandidate> candidates = search(variant, near != null ? 5 : 1, near).stream()
                .filter(c -> !c.partial())
                .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            if (!variant.equals(address)) {
                log.info("Kunne ikke finde '{}' præcist, brugte i stedet '{}'", address, variant);
            }
            GeocodeCandidate best = near != null ? nearest(candidates, near) : candidates.get(0);
            return Optional.of(new GeocodeResult(best.lat(), best.lon()));
        }
        return Optional.empty();
    }

    private GeocodeCandidate nearest(List<GeocodeCandidate> candidates, GeocodeResult near) {
        return candidates.stream()
            .min(Comparator.comparingDouble(c -> DistanceUtil.kmBetween(near.lat(), near.lon(), c.lat(), c.lon())))
            .orElseThrow();
    }

    /** Progressively relaxed variants of an address, tried in order until one resolves. */
    private List<String> addressVariants(String address) {
        List<String> variants = new ArrayList<>();
        variants.add(address);

        String floorStripped = stripFloorSegments(address);
        addIfNew(variants, floorStripped);

        // Drop everything after the street+number (city/postcode) — a wrong or
        // non-matching city qualifier can make Nominatim fail the whole query even
        // when the street itself exists (just elsewhere), so try it bare too.
        String streetOnly = firstSegment(floorStripped);
        addIfNew(variants, streetOnly);

        String withoutHouseNumber = stripHouseNumber(streetOnly);
        addIfNew(variants, withoutHouseNumber);

        return variants;
    }

    private void addIfNew(List<String> variants, String candidate) {
        if (candidate != null && !candidate.isBlank() && !variants.contains(candidate)) {
            variants.add(candidate);
        }
    }

    private String firstSegment(String address) {
        int comma = address.indexOf(',');
        return comma < 0 ? address : address.substring(0, comma).trim();
    }

    private String stripFloorSegments(String address) {
        String[] parts = address.split(",");
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String segment = parts[i].trim();
            // Never drop the first segment (the street+number) even if it looks similar.
            if (i > 0 && FLOOR_OR_DOOR_SEGMENT.matcher(segment).matches()) {
                continue;
            }
            kept.add(segment);
        }
        return String.join(", ", kept);
    }

    private String stripHouseNumber(String address) {
        var matcher = LEADING_HOUSE_NUMBER.matcher(address.trim());
        if (!matcher.matches()) {
            return null;
        }
        String street = matcher.group(1).trim();
        String rest = matcher.group(2);
        return rest != null ? street + rest : street;
    }

    private List<GeocodeCandidate> searchWithDawa(String query, int limit, GeocodeResult near) throws Exception {
        String cleaned = stripTrailingFloor(query.trim());
        if (cleaned.length() < 3) {
            return List.of();
        }
        // With a reference point, fetch extra so the nearby matches can be moved to the front.
        int fetch = near != null ? Math.max(limit, 100) : limit;
        String url = "https://api.dataforsyningen.dk/autocomplete?type=adgangsadresse&fuzzy=&per_side=" + fetch
            + "&q=" + URLEncoder.encode(cleaned, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        List<GeocodeCandidate> addresses = new ArrayList<>();
        List<GeocodeCandidate> streets = new ArrayList<>();
        JsonNode root = mapper.readTree(response.body());
        for (JsonNode node : root) {
            String label = node.path("tekst").asText();
            JsonNode data = node.path("data");
            if (data.path("x").isNumber() && data.path("y").isNumber()) {
                addresses.add(new GeocodeCandidate(label, data.path("y").asDouble(), data.path("x").asDouble()));
            } else if ("vejnavn".equals(node.path("type").asText())) {
                streets.add(new GeocodeCandidate(label, 0, 0, true));
            }
        }
        List<GeocodeCandidate> result = addresses.isEmpty() ? streets : addresses;
        if (near != null && !addresses.isEmpty()) {
            List<GeocodeCandidate> ordered = new ArrayList<>();
            addresses.stream().filter(c -> isNearby(c, near)).forEach(ordered::add);
            addresses.stream().filter(c -> !isNearby(c, near)).forEach(ordered::add);
            result = ordered;
        }
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    private boolean isNearby(GeocodeCandidate c, GeocodeResult near) {
        return DistanceUtil.kmBetween(near.lat(), near.lon(), c.lat(), c.lon()) <= NEARBY_KM;
    }

    /**
     * "Scharlingevej 21, 3 th" → "Scharlingevej 21". Floor/door in the search text throws the
     * fuzzy match off (it starts matching other streets), and floor/door belongs in the note anyway.
     */
    public static String stripTrailingFloor(String query) {
        String cleaned = query;
        String previous;
        do {
            previous = cleaned;
            cleaned = TRAILING_FLOOR_DOOR.matcher(cleaned).replaceFirst("");
        } while (!cleaned.equals(previous));
        return cleaned.isBlank() ? query : cleaned.trim();
    }

    private List<GeocodeCandidate> searchWithGoogle(String query, int limit) throws Exception {
        String url = "https://maps.googleapis.com/maps/api/geocode/json?region=dk&address="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&key=" + URLEncoder.encode(googleApiKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());
        if (!"OK".equals(root.path("status").asText())) {
            return List.of();
        }
        List<GeocodeCandidate> candidates = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            if (candidates.size() >= limit) break;
            JsonNode location = result.path("geometry").path("location");
            candidates.add(new GeocodeCandidate(
                result.path("formatted_address").asText(),
                location.path("lat").asDouble(),
                location.path("lng").asDouble()));
        }
        return candidates;
    }

    private synchronized List<GeocodeCandidate> searchWithNominatim(String query, int limit) throws Exception {
        throttleNominatim();
        String url = "https://nominatim.openstreetmap.org/search?format=json&addressdetails=0&countrycodes=dk&limit="
            + limit + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "kokkens-madpakke-route-planner/1.0")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        lastNominatimCallMillis = System.currentTimeMillis();
        JsonNode root = mapper.readTree(response.body());
        List<GeocodeCandidate> candidates = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode result : root) {
                candidates.add(new GeocodeCandidate(
                    result.path("display_name").asText(),
                    result.path("lat").asDouble(),
                    result.path("lon").asDouble()));
            }
        }
        return candidates;
    }

    private void throttleNominatim() throws InterruptedException {
        long sinceLast = System.currentTimeMillis() - lastNominatimCallMillis;
        if (sinceLast < 1100) {
            Thread.sleep(1100 - sinceLast);
        }
    }
}
