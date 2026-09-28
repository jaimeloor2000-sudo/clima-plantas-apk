package ec.planta.climaplantas;

import android.content.Context;
import android.content.SharedPreferences;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee los boletines que publica el INAMHI (PDF en direcciones fijas que se reemplazan
 * con cada boletín nuevo) y avisa cuando sale uno nuevo que menciona la zona de las plantas.
 */
public final class Inamhi {

    /** id, nombre, dirección del PDF, ¿alarma con carillón? (1 = sí, 0 = aviso normal) */
    static final String[][] BOLETINES = {
            {"advertencia", "Alerta temprana: advertencia", "https://www.inamhi.gob.ec/pronostico/advertencia.pdf", "1"},
            {"desarrollo", "Alerta temprana: en desarrollo", "https://www.inamhi.gob.ec/pronostico/desarrollo.pdf", "1"},
            {"nowcasting", "Aviso a muy corto plazo", "https://www.inamhi.gob.ec/pronostico/nowcasting.pdf", "1"},
            {"hidrologico", "Aviso hidrológico", "https://www.inamhi.gob.ec/html/Alerta_hidrologica_EHA.pdf", "1"},
            {"declive", "Alerta temprana: declive", "https://www.inamhi.gob.ec/pronostico/declive.pdf", "0"},
            {"cancelacion", "Alerta temprana: cancelación", "https://www.inamhi.gob.ec/pronostico/cancelacion.pdf", "0"},
    };

    /** Palabras que indican que el boletín incluye la zona de las plantas. */
    static final String[] ZONA = {"guayas", "guayaquil", "daule", "sargentillo", "nobol", "pedro carbo",
            "santa lucia", "isidro ayora", "litoral", "costa"};
    /** Estas cuentan como zona solo si no aparece ninguna de las específicas (sirven para boletines regionales). */
    static final int ZONA_ESPECIFICAS = 8;

    static final String PREFS = "inamhi";
    static final long INTERVALO_MS = 30 * 60 * 1000L;
    private static final ZoneId TZ = ZoneId.of("America/Guayaquil");
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    private Inamhi() {}

    static synchronized void revisar(Context c, boolean forzar) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long ahoraMs = System.currentTimeMillis();
        if (!forzar && ahoraMs - sp.getLong("ultima", 0) < INTERVALO_MS) return;
        try {
            PDFBoxResourceLoader.init(c.getApplicationContext());
        } catch (Throwable ignored) { }
        SharedPreferences.Editor ed = sp.edit();
        ed.putLong("ultima", ahoraMs);
        boolean error = false;

        for (String[] b : BOLETINES) {
            String id = b[0];
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(b[2]).openConnection();
                con.setConnectTimeout(20000);
                con.setReadTimeout(45000);
                con.setRequestProperty("User-Agent", "ClimaPlantas/1.0 (Android)");
                String etag = sp.getString(id + ".etag", null);
                String lm = sp.getString(id + ".lm", null);
                if (etag != null) con.setRequestProperty("If-None-Match", etag);
                if (lm != null) con.setRequestProperty("If-Modified-Since", lm);
                int code = con.getResponseCode();
                if (code == 304) continue;                       // sin cambios
                if (code == 404) { ed.putString(id + ".estado", "no publicado"); continue; }
                if (code != 200) { ed.putString(id + ".estado", "sin respuesta (" + code + ")"); error = true; continue; }

                byte[] pdf;
                try (InputStream in = con.getInputStream()) { pdf = leer(in); }
                String nEtag = con.getHeaderField("ETag"), nLm = con.getHeaderField("Last-Modified");
                if (nEtag != null) ed.putString(id + ".etag", nEtag);
                if (nLm != null) ed.putString(id + ".lm", nLm);

                String huella = sha1(pdf);
                if (huella.equals(sp.getString(id + ".huella", ""))) { ed.remove(id + ".estado"); continue; }

                String texto;
                try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
                    PDFTextStripper st = new PDFTextStripper();
                    st.setEndPage(4);
                    texto = st.getText(doc);
                }

                JSONObject j = analizar(texto);
                j.put("id", id);
                j.put("nombre", b[1]);
                j.put("url", b[2]);
                j.put("leido", LocalDateTime.now(TZ).withNano(0).toString());
                ed.putString(id + ".json", j.toString());
                ed.putString(id + ".huella", huella);
                ed.remove(id + ".estado");

                // ¿Avisar? Solo si el boletín es nuevo, menciona la zona y es reciente.
                String numero = j.optString("numero", huella.substring(0, 8));
                String yaAvisado = sp.getString(id + ".avisado", "");
                boolean reciente = !j.has("dias") || j.optInt("dias", 0) <= 2;
                if (j.optBoolean("zona") && reciente && !numero.equals(yaAvisado)) {
                    boolean carillon = b[3].equals("1");
                    StringBuilder msg = new StringBuilder();
                    if (j.has("fenomeno")) msg.append(j.getString("fenomeno")).append(".\n");
                    if (j.has("estatus")) msg.append("Estatus: ").append(j.getString("estatus")).append(". ");
                    msg.append("Menciona: ").append(j.optString("lugares", "Guayas")).append(".");
                    if (j.has("emision")) msg.append("\nEmitido: ").append(j.getString("emision")).append(".");
                    msg.append("\n\nAbre la app → Avisos del INAMHI para ver el boletín completo.");
                    String titulo = "AVISO INAMHI · " + b[1] + (j.has("numero") ? " Nro. " + j.getString("numero") : "");
                    Notificador.enviar(c, titulo, msg.toString(), Math.abs(("inamhi" + id).hashCode()), carillon);
                    ed.putString(id + ".avisado", numero);
                }
            } catch (Exception e) {
                error = true;
                ed.putString(id + ".estado", "error: " + e.getClass().getSimpleName());
            } finally {
                if (con != null) con.disconnect();
            }
        }
        ed.putBoolean("error", error);
        ed.apply();
    }

    /** Saca del texto del PDF: número, estatus, fenómeno, fecha de emisión y lugares de la zona. */
    static JSONObject analizar(String texto) throws Exception {
        JSONObject j = new JSONObject();
        String limpio = texto.replace(' ', ' ');
        String plano = sinTildes(limpio).toLowerCase(Locale.ROOT);

        Matcher m = Pattern.compile("(?i)\\bN(?:ro\\.?|o\\.|°|º)\\s*[:.]?\\s*(\\d{1,4})").matcher(limpio);
        if (m.find()) j.put("numero", m.group(1));

        m = Pattern.compile("(?i)Estatus\\s*:?\\s*([A-Za-zÁÉÍÓÚÑáéíóúñ]+)").matcher(limpio);
        if (m.find()) j.put("estatus", m.group(1).toUpperCase(Locale.ROOT));

        String[] claves = {"LLUVIA", "TORMENTA", "VIENTO", "TEMPERATURA", "CAUDAL", "CRECIDA", "OLEAJE",
                "PRECIPITACI", "INUNDACI", "DESBORDAMIENTO", "RADIACI", "GRANIZO", "NIVEL"};
        for (String linea : limpio.split("\\r?\\n")) {
            String l = linea.trim();
            if (l.length() < 12 || l.length() > 160) continue;
            if (!l.equals(l.toUpperCase(Locale.ROOT))) continue;
            String lp = sinTildes(l);
            boolean ok = false;
            for (String k : claves) if (lp.contains(k)) { ok = true; break; }
            if (ok) { j.put("fenomeno", l); break; }
        }

        m = Pattern.compile("(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)(?:\\s+(?:de|del)\\s+(\\d{4}))?")
                .matcher(plano);
        if (m.find()) {
            int dia = Integer.parseInt(m.group(1));
            String mes = m.group(2).equals("setiembre") ? "septiembre" : m.group(2);
            int nm = 1;
            for (int i = 0; i < MESES.length; i++) if (MESES[i].equals(mes)) nm = i + 1;
            LocalDate hoy = LocalDate.now(TZ);
            int anio = m.group(3) != null ? Integer.parseInt(m.group(3)) : hoy.getYear();
            try {
                LocalDate f = LocalDate.of(anio, nm, dia);
                if (m.group(3) == null && f.isAfter(hoy.plusDays(1))) f = f.minusYears(1);
                j.put("emision", dia + " de " + mes + " de " + f.getYear());
                j.put("fecha", f.toString());
                j.put("dias", (int) ChronoUnit.DAYS.between(f, hoy));
            } catch (Exception ignored) { }
        }

        Set<String> lugares = new LinkedHashSet<>();
        for (int i = 0; i < ZONA_ESPECIFICAS; i++) {
            if (Pattern.compile("\\b" + ZONA[i] + "\\b").matcher(plano).find()) lugares.add(titulo(ZONA[i]));
        }
        boolean regional = false;
        if (lugares.isEmpty()) {
            for (int i = ZONA_ESPECIFICAS; i < ZONA.length; i++) {
                if (Pattern.compile("\\b" + ZONA[i] + "\\b").matcher(plano).find()) { lugares.add(titulo(ZONA[i])); regional = true; }
            }
        }
        j.put("zona", !lugares.isEmpty());
        j.put("regional", regional);
        if (!lugares.isEmpty()) j.put("lugares", String.join(", ", lugares));

        String extracto = limpio.replaceAll("\\s+", " ").trim();
        if (extracto.length() > 700) extracto = extracto.substring(0, 700) + "…";
        j.put("extracto", extracto);
        return j;
    }

    /** Todo lo guardado, para mostrarlo en la pantalla. */
    static String comoJson(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (String[] b : BOLETINES) {
            try {
                String s = sp.getString(b[0] + ".json", null);
                JSONObject j = s != null ? new JSONObject(s) : new JSONObject().put("id", b[0]).put("nombre", b[1]).put("url", b[2]);
                String estado = sp.getString(b[0] + ".estado", null);
                if (estado != null) j.put("estado", estado);
                if (j.has("fecha")) {
                    j.put("dias", (int) ChronoUnit.DAYS.between(LocalDate.parse(j.getString("fecha")), LocalDate.now(TZ)));
                }
                arr.put(j);
            } catch (Exception ignored) { }
        }
        try {
            return new JSONObject().put("boletines", arr).put("ultima", sp.getLong("ultima", 0))
                    .put("error", sp.getBoolean("error", false)).toString();
        } catch (Exception e) {
            return "{\"boletines\":[]}";
        }
    }

    private static String titulo(String s) {
        StringBuilder sb = new StringBuilder();
        for (String p : s.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String sinTildes(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static byte[] leer(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String sha1(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
