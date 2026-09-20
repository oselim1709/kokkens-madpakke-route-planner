package dk.madpakke.service;

import dk.madpakke.domain.Route;
import dk.madpakke.domain.Stop;
import dk.madpakke.domain.StopType;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Builds a plain-text version of a route, ready to copy/paste into a chat message. */
@Service
public class RouteTextFormatter {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public String format(Route route) {
        StringBuilder sb = new StringBuilder();
        String driver = route.getDriverName() != null ? route.getDriverName() : "Ikke tildelt";
        sb.append("Rute ").append(route.getSequenceIndex() + 1).append(" – Chauffør: ").append(driver).append('\n');
        sb.append("Estimeret tid: ").append(formatMinutes(route.getEstimatedMinutes())).append('\n');
        if (route.getGoogleMapsUrl() != null) {
            sb.append("Naviger: ").append(route.getGoogleMapsUrl()).append('\n');
            if (route.getEndAddress() != null) {
                sb.append("Slutadresse: ").append(route.getEndAddress()).append('\n');
            }
            if (route.getGoogleMapsExcludedStopCount() > 0) {
                int coveredCount = route.getStops().size() - route.getGoogleMapsExcludedStopCount();
                sb.append("⚠ OBS: Google Maps kan kun tage 10 stop ad gangen (inkl. startpunkt"
                    + (route.getEndAddress() != null ? " og slutadresse" : "") + "). Linket dækker kun stop 1-")
                    .append(coveredCount)
                    .append(". Efter dem skal du navigere manuelt til de resterende ")
                    .append(route.getGoogleMapsExcludedStopCount())
                    .append(" stop nedenfor.\n");
            }
        }
        sb.append('\n');

        String totals = formatItems(sumQuantities(route.getStops()));
        if (!totals.isEmpty()) {
            sb.append("Total til pakning: ").append(totals).append('\n');
            sb.append('\n');
        }

        int i = 1;
        for (Stop stop : route.getStops()) {
            sb.append(i++).append(") ").append(stop.getCustomerName())
                .append(" – ").append(stop.getAddress());
            if (stop.getFloorDoor() != null && !stop.getFloorDoor().isBlank()) {
                sb.append(" (etage/dør: ").append(stop.getFloorDoor().trim()).append(')');
            }
            sb.append('\n');

            sb.append("   Type: ").append(stop.getStopType() == StopType.GYM ? "Gym" : "Privat");
            if (stop.getDeadline() != null) {
                sb.append(" | Deadline: ").append(stop.getDeadline().format(TIME_FORMAT));
            }
            sb.append('\n');

            String items = formatItems(quantitiesOf(stop));
            if (!items.isEmpty()) {
                sb.append("   Varer: ").append(items).append('\n');
            }
            if (stop.getSpecialOrder() != null && !stop.getSpecialOrder().isBlank()) {
                sb.append("   Special: ").append(stop.getSpecialOrder().trim()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static final String[] ITEM_LABELS = {
        "Normal madpakke", "Fitness madpakke", "Müslibar", "Frugt", "Risengrød", "Sandwich", "Kage"
    };

    private int[] quantitiesOf(Stop stop) {
        return new int[] {
            stop.getQtyNormalLunchbox(), stop.getQtyFitnessLunchbox(), stop.getQtyMusliBar(),
            stop.getQtyFruit(), stop.getQtyRisengroed(), stop.getQtySandwich(), stop.getQtyCake()
        };
    }

    private int[] sumQuantities(List<Stop> stops) {
        int[] totals = new int[ITEM_LABELS.length];
        for (Stop stop : stops) {
            int[] qty = quantitiesOf(stop);
            for (int i = 0; i < totals.length; i++) {
                totals[i] += qty[i];
            }
        }
        return totals;
    }

    private String formatItems(int[] quantities) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < quantities.length; i++) {
            if (quantities[i] > 0) {
                parts.add(quantities[i] + "x " + ITEM_LABELS[i]);
            }
        }
        return String.join(", ", parts);
    }

    public static String formatMinutes(double minutes) {
        int total = (int) Math.round(minutes);
        int hours = total / 60;
        int mins = total % 60;
        if (hours > 0) {
            return hours + "t " + mins + " min";
        }
        return mins + " min";
    }
}
