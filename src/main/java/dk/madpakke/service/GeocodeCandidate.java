package dk.madpakke.service;

/**
 * One address suggestion returned while the user is typing, with its coordinates already resolved.
 *
 * @param partial true for a street name without a house number yet: picking it only completes the
 *                text (the user still has to type the number), so lat/lon are not meaningful.
 */
public record GeocodeCandidate(String label, double lat, double lon, boolean partial) {

    public GeocodeCandidate(String label, double lat, double lon) {
        this(label, lat, lon, false);
    }
}
