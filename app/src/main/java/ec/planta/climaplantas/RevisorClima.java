package ec.planta.climaplantas;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Revisa el pronóstico cada 15 minutos en segundo plano, aunque la app esté cerrada. */
public class RevisorClima extends JobService {

    static final int JOB_ID = 4201;
    static final String PREFS = "clima";

    static void programar(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(c, RevisorClima.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(ji);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            revisar(ctx);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static synchronized void revisar(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject cfg = new JSONObject(leerAsset(c, "config.json"));
            String tz = cfg.getString("zona_horaria");
            JSONArray ubics = cfg.getJSONArray("ubicaciones");
            JSONObject uj = cfg.getJSONObject("umbrales");
            Reglas.Umbrales u = new Reglas.Umbrales();
            u.aguacero15 = uj.getDouble("aguacero_15min_mm");
            u.lluviaHora = uj.getDouble("lluvia_hora_mm");
            u.lluviaDia = uj.getDouble("lluvia_dia_mm");
            u.inund24 = uj.getDouble("inundacion_24h_mm");
            u.inund72 = uj.getDouble("inundacion_72h_mm");
            u.uv = uj.getDouble("uv_extremo");
            u.sensacion = uj.getDouble("sensacion_termica_c");

            StringBuilder lat = new StringBuilder(), lon = new StringBuilder();
            for (int i = 0; i < ubics.length(); i++) {
                if (i > 0) { lat.append(','); lon.append(','); }
                lat.append(ubics.getJSONObject(i).getDouble("lat"));
                lon.append(ubics.getJSONObject(i).getDouble("lon"));
            }
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&hourly=precipitation,uv_index,apparent_temperature"
                    + "&minutely_15=precipitation&forecast_days=4&timezone="
                    + URLEncoder.encode(tz, "UTF-8");
            String cuerpo = http(url).trim();
            JSONArray datos = cuerpo.startsWith("[") ? new JSONArray(cuerpo) : new JSONArray().put(new JSONObject(cuerpo));

            LocalDateTime ahora = LocalDateTime.now(ZoneId.of(tz));
            SharedPreferences.Editor ed = sp.edit();
            int enviadas = 0;
            for (int i = 0; i < ubics.length() && i < datos.length(); i++) {
                JSONObject ub = ubics.getJSONObject(i);
                JSONObject d = datos.getJSONObject(i);
                JSONObject h = d.getJSONObject("hourly");
                LocalDateTime[] horas = tiempos(h.getJSONArray("time"));
                double[] ll = numeros(h.getJSONArray("precipitation"));
                double[] uv = numeros(h.getJSONArray("uv_index"));
                double[] st = numeros(h.getJSONArray("apparent_temperature"));
                LocalDateTime[] t15 = null;
                double[] l15 = null;
                JSONObject m = d.optJSONObject("minutely_15");
                if (m != null) {
                    t15 = tiempos(m.getJSONArray("time"));
                    l15 = numeros(m.getJSONArray("precipitation"));
                }
                List<Reglas.Riesgo> rs = Reglas.evaluar(horas, ll, uv, st, t15, l15, ahora, u);
                for (Reglas.Riesgo r : rs) {
                    String clave = "a:" + Reglas.clave(ub.getString("id"), r);
                    if (sp.contains(clave)) continue;
                    boolean sirena = r.tipo.equals("lluvia") || r.tipo.equals("aguacero") || r.tipo.equals("inundacion");
                    Notificador.enviar(c, "ALERTA " + ub.getString("nombre"), Reglas.mensaje(r),
                            Math.abs(clave.hashCode()), sirena);
                    ed.putString(clave, ahora.toString());
                    enviadas++;
                }
            }
            // limpiar registros de más de 7 días
            String limite = LocalDate.now(ZoneId.of(tz)).minusDays(7).toString();
            List<String> borrar = new ArrayList<>();
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String k = e.getKey();
                if (!k.startsWith("a:")) continue;
                String[] p = k.split("\\|");
                if (p.length >= 3 && p[2].compareTo(limite) < 0) borrar.add(k);
            }
            for (String k : borrar) ed.remove(k);
            ed.putString("ultima_revision", ahora.withNano(0).toString());
            ed.putInt("ultimas_enviadas", enviadas);
            ed.remove("ultimo_error");
            ed.apply();
        } catch (Exception e) {
            sp.edit().putString("ultimo_error", String.valueOf(e.getMessage())).apply();
        }
        // Boletines del INAMHI (cada 30 minutos)
        try {
            Inamhi.revisar(c, false);
        } catch (Throwable ignored) { }
    }

    private static LocalDateTime[] tiempos(JSONArray a) throws Exception {
        LocalDateTime[] t = new LocalDateTime[a.length()];
        for (int i = 0; i < a.length(); i++) t[i] = LocalDateTime.parse(a.getString(i));
        return t;
    }

    private static double[] numeros(JSONArray a) {
        double[] v = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            double x = a.optDouble(i, 0);
            v[i] = Double.isNaN(x) ? 0 : x;
        }
        return v;
    }

    static String leerAsset(Context c, String nombre) throws Exception {
        try (InputStream in = c.getAssets().open(nombre)) {
            return leer(in);
        }
    }

    static String http(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        try (InputStream in = con.getInputStream()) {
            return leer(in);
        } finally {
            con.disconnect();
        }
    }

    private static String leer(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
