package dk.madpakke.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches real road-network driving times between a set of points, using OSRM's free
 * public demo routing server (no API key, no cost — but also no uptime guarantee, so
 * every caller must be prepared for {@link #fetchDurationMatrixMinutes} to return null
 * and fall back to a straight-line estimate).
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @return an NxN matrix (minutes) where result[i][j] is the driving time from
     *         points.get(i) to points.get(j), or null if OSRM couldn't be reached or
     *         returned something unusable.
     */
    public double[][] fetchDurationMatrixMinutes(List<GeocodeResult> points) {
        if (points == null || points.size() < 2) {
            return null;
        }
        try {
            String coords = points.stream()
                .map(p -> String.format(Locale.ROOT, "%.6f,%.6f", p.lon(), p.lat()))
                .collect(Collectors.joining(";"));
            String url = "https://router.project-osrm.org/table/v1/driving/" + coords + "?annotations=duration";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (!"Ok".equals(root.path("code").asText())) {
                log.warn("OSRM returnerede status '{}', falder tilbage til lige-linje-estimat", root.path("code").asText());
                return null;
            }
            JsonNode durationsNode = root.path("durations");
            int n = points.size();
            double[][] result = new double[n][n];
            for (int i = 0; i < n; i++) {
                JsonNode row = durationsNode.get(i);
                for (int j = 0; j < n; j++) {
                    JsonNode cell = row.get(j);
                    result[i][j] = (cell == null || cell.isNull()) ? Double.NaN : cell.asDouble() / 60.0;
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Kunne ikke hente rigtige køretider (OSRM), falder tilbage til lige-linje-estimat: {}", e.getMessage());
            return null;
        }
    }
}
