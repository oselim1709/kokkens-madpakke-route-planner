package dk.madpakke.service;

/** One address suggestion returned while the user is typing, with its coordinates already resolved. */
public record GeocodeCandidate(String label, double lat, double lon) {
}
