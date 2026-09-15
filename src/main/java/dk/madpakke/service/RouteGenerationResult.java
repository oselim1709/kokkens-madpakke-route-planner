package dk.madpakke.service;

import dk.madpakke.domain.Route;
import java.util.List;

public record RouteGenerationResult(List<Route> routes, List<String> skippedStops) {
}
