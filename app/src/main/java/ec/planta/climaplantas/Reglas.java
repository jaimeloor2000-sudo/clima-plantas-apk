package ec.planta.climaplantas;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reglas de alarma. Mismos umbrales que muestra la app (config.json → umbrales). */
public final class Reglas {

    public static final class Umbrales {
        public double aguacero15, lluviaHora, lluviaDia, inund24, inund72, uv, sensacion;
    }

    public static final class Riesgo {
        public final String tipo;
        public final String texto;
        public final LocalDateTime hora;
        public final boolean bloque; // true = puede repetirse cada 3 horas

        Riesgo(String tipo, String texto, LocalDateTime hora, boolean bloque) {
            this.tipo = tipo; this.texto = texto; this.hora = hora; this.bloque = bloque;
        }
    }

    public static final Map<String, String[]> PRECAUCIONES = new HashMap<>();
    static {
        PRECAUCIONES.put("aguacero", new String[]{
                "Cubrir ya el material y pellet al aire libre",
                "Suspender carga y descarga en patio",
                "Verificar que rejillas y drenajes estén libres"});
        PRECAUCIONES.put("lluvia", new String[]{
                "Despejar canaletas, rejillas y drenajes",
                "Cubrir material, pacas y pellet a la intemperie",
                "Señalizar pisos mojados en patio de carga",
                "Proteger tableros eléctricos y motores expuestos"});
        PRECAUCIONES.put("inundacion", new String[]{
                "Subir material, pellet y repuestos del nivel del piso",
                "Verificar bombas de achique y generador",
                "Cortar energía en zonas que puedan inundarse",
                "Evitar vías anegadas para camiones y personal"});
        PRECAUCIONES.put("calor", new String[]{
                "Hidratación cada 20-30 minutos",
                "Pausas a la sombra en patio y extrusión",
                "Vigilar temperatura de motores y extrusoras"});
        PRECAUCIONES.put("uv", new String[]{
                "Protector solar, gorra y manga larga afuera",
                "Evitar trabajo exterior de 11:00 a 15:00"});
    }

    private static final Locale ES = Locale.forLanguageTag("es-EC");

    private static String n0(double v) { return String.format(ES, "%.0f", v); }
    private static String hh(LocalDateTime t) { return String.format(ES, "%02d:%02d", t.getHour(), t.getMinute()); }

    private Reglas() {}

    /**
     * @param horas   horas del pronóstico (hora local)
     * @param lluvia  mm por hora
     * @param uv      índice UV por hora
     * @param sens    sensación térmica por hora (°C)
     * @param t15     tiempos cada 15 min (puede ser null)
     * @param lluvia15 mm cada 15 min (puede ser null)
     * @param ahora   hora local actual
     */
    public static List<Riesgo> evaluar(LocalDateTime[] horas, double[] lluvia, double[] uv, double[] sens,
                                       LocalDateTime[] t15, double[] lluvia15, LocalDateTime ahora, Umbrales u) {
        List<Riesgo> out = new ArrayList<>();
        LocalDateTime inicio = ahora.withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fin24 = inicio.plusHours(24), fin72 = inicio.plusHours(72);

        double r24 = 0, r72 = 0;
        int iR = -1, iU = -1, iS = -1;
        for (int i = 0; i < horas.length; i++) {
            LocalDateTime t = horas[i];
            if (t.isBefore(inicio)) continue;
            if (t.isBefore(fin72)) r72 += lluvia[i];
            if (!t.isBefore(fin24)) continue;
            r24 += lluvia[i];
            if (iR < 0 || lluvia[i] > lluvia[iR]) iR = i;
            if (iU < 0 || uv[i] > uv[iU]) iU = i;
            if (iS < 0 || sens[i] > sens[iS]) iS = i;
        }

        // Aguacero inminente: próximas 2 horas, datos cada 15 minutos
        if (t15 != null && lluvia15 != null) {
            double total2h = 0, pico = -1;
            LocalDateTime tPico = null;
            LocalDateTime fin2h = ahora.plusHours(2);
            LocalDateTime desde = ahora.minusMinutes(14);
            for (int i = 0; i < t15.length; i++) {
                LocalDateTime t = t15[i];
                if (t.isBefore(desde) || !t.isBefore(fin2h)) continue;
                total2h += lluvia15[i];
                if (lluvia15[i] > pico) { pico = lluvia15[i]; tPico = t; }
            }
            if (tPico != null && (pico >= u.aguacero15 || total2h >= u.lluviaHora)) {
                out.add(new Riesgo("aguacero",
                        "Aguacero inminente: " + n0(total2h) + " mm en las próximas 2 horas, más fuerte cerca de las " + hh(tPico),
                        tPico, true));
            }
        }

        if (iR < 0) return out;

        if (r24 >= u.inund24 || r72 >= u.inund72) {
            out.add(new Riesgo("inundacion",
                    "Riesgo de inundación: " + n0(r24) + " mm en 24 h (" + n0(r72) + " mm en 3 días)", horas[iR], false));
        }
        if (lluvia[iR] >= u.lluviaHora || r24 >= u.lluviaDia) {
            out.add(new Riesgo("lluvia",
                    "Lluvia muy fuerte: hasta " + n0(lluvia[iR]) + " mm/h cerca de las " + hh(horas[iR])
                            + ", total " + n0(r24) + " mm en 24 h", horas[iR], false));
        }
        // La alarma de calor se quitó a pedido: la sensación térmica queda solo como información en la pantalla.
        if (uv[iU] >= u.uv) {
            out.add(new Riesgo("uv",
                    "Sol extremo: índice UV " + n0(uv[iU]) + " a las " + hh(horas[iU]), horas[iU], false));
        }
        return out;
    }

    /** Clave para no repetir la misma alarma: una por día (el aguacero, una cada 3 horas). */
    public static String clave(String idUbicacion, Riesgo r) {
        String k = idUbicacion + "|" + r.tipo + "|" + r.hora.toLocalDate();
        if (r.bloque) k += "|" + (r.hora.getHour() / 3);
        return k;
    }

    public static String mensaje(Riesgo r) {
        StringBuilder sb = new StringBuilder(r.texto).append(".\n\nPrecauciones:");
        for (String p : PRECAUCIONES.get(r.tipo)) sb.append("\n• ").append(p);
        sb.append("\n\nAlertas oficiales: inamhi.gob.ec · gestionderiesgos.gob.ec");
        return sb.toString();
    }
}
