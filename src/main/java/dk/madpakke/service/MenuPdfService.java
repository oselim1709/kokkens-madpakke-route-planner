package dk.madpakke.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import dk.madpakke.domain.MenuLocation;
import dk.madpakke.domain.WeeklyDish;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Renders the approved menu-card design to a print-ready A4 PDF, one per pickup location.
 *
 * Uses a pure-Java renderer (openhtmltopdf) instead of a headless-browser/Puppeteer-style
 * approach so the app stays a single self-contained jar with no external browser binary to
 * install — the tradeoff is that the source template (adapted from the approved HTML/CSS)
 * uses table-based layout instead of flexbox and a real border instead of box-shadow, since
 * this renderer doesn't support either. Fonts are bundled and registered explicitly (rather
 * than relying on system fonts like Georgia/Helvetica, which a Linux server won't have) so
 * Danish characters (æ/ø/å) always render correctly regardless of where this is deployed.
 */
@Service
public class MenuPdfService {

    private final String template;
    private final String logoDataUri;
    private final String mobilePayLogoDataUri;

    private final byte[] ptSerifRegular;
    private final byte[] ptSerifBold;
    private final byte[] ptSerifItalic;
    private final byte[] arimoRegular;
    private final byte[] arimoBold;

    public MenuPdfService() throws IOException {
        this.template = readResourceAsString("/menu-assets/menu_card_template.xhtml");
        this.logoDataUri = toDataUri("/menu-assets/logo_clean.png");
        this.mobilePayLogoDataUri = toDataUri("/menu-assets/mp_logo_text.png");

        this.ptSerifRegular = readResourceBytes("/fonts/PTSerif-Regular.ttf");
        this.ptSerifBold = readResourceBytes("/fonts/PTSerif-Bold.ttf");
        this.ptSerifItalic = readResourceBytes("/fonts/PTSerif-Italic.ttf");
        this.arimoRegular = readResourceBytes("/fonts/Arimo-Regular.ttf");
        this.arimoBold = readResourceBytes("/fonts/Arimo-Bold.ttf");
    }

    public byte[] render(MenuLocation location, WeeklyDish dish) throws IOException {
        String descLine = isBlank(dish.getDescription()) ? ""
            : "<div class=\"item-desc\">" + escape(dish.getDescription()) + "</div>";
        String metaLine = (isBlank(dish.getProteinGrams()) && isBlank(dish.getKcal())) ? ""
            : "<div class=\"item-meta\">" + escape(dish.getProteinGrams()) + " g protein &#160;&#183;&#160; "
                + escape(dish.getKcal()) + " kcal</div>";
        String allergenLine = isBlank(dish.getAllergens()) ? ""
            : "<div class=\"item-allergen\">Allergener: " + escape(dish.getAllergens()) + "</div>";

        String html = template
            .replace("{{LOGO_SRC}}", logoDataUri)
            .replace("{{MOBILEPAY_LOGO_SRC}}", mobilePayLogoDataUri)
            .replace("{{LOCATION}}", escape(location.getName()))
            .replace("{{MOBILEPAY_NUMBER}}", escape(location.getMobilePayNumber()))
            .replace("{{DISH_NAME}}", escape(isBlank(dish.getName()) ? "Ugens ret" : dish.getName()))
            .replace("{{DISH_SUBTITLE}}", escape(dish.getSubtitle()))
            .replace("{{DISH_PRICE}}", escape(isBlank(dish.getPrice()) ? "69" : dish.getPrice()))
            .replace("{{DISH_DESC_LINE}}", descLine)
            .replace("{{DISH_META_LINE}}", metaLine)
            .replace("{{DISH_ALLERGEN_LINE}}", allergenLine);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFont(() -> new ByteArrayInputStream(ptSerifRegular), "PT Serif", 400, FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(ptSerifBold), "PT Serif", 700, FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(ptSerifItalic), "PT Serif", 400, FontStyle.ITALIC, true);
        builder.useFont(() -> new ByteArrayInputStream(arimoRegular), "Arimo", 400, FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(arimoBold), "Arimo", 700, FontStyle.NORMAL, true);
        builder.withHtmlContent(html, null);
        builder.toStream(out);
        builder.run();
        return out.toByteArray();
    }

    private static String readResourceAsString(String classpathLocation) throws IOException {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readResourceBytes(String classpathLocation) throws IOException {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static String toDataUri(String classpathLocation) throws IOException {
        byte[] bytes = readResourceBytes(classpathLocation);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
