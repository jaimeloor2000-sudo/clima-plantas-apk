package ec.planta.climaplantas;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;

public class MainActivity extends Activity {

    /** Dirección interna: los archivos de la app se sirven desde el propio celular. */
    static final String HOST = "app.clima.local";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0A6E8A);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Puente(this, web), "AppAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(tipo(path), "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "No encontrado",
                            new HashMap<>(), new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (req.isForMainFrame() && !HOST.equals(u.getHost())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) { }
                    return true; // los enlaces externos se abren en el navegador
                }
                return false;
            }
        });
        web.loadUrl("https://" + HOST + "/index.html");

        Notificador.crearCanal(this);
        RevisorClima.programar(this);
        new Thread(() -> RevisorClima.revisar(getApplicationContext())).start();
        pedirPermisoNotificaciones();
    }

    private static String tipo(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        return "application/octet-stream";
    }

    private void pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            pedirSinRestriccionBateria();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        pedirSinRestriccionBateria();
    }

    /** Pide (una sola vez) que el sistema no frene la revisión en segundo plano. */
    private void pedirSinRestriccionBateria() {
        SharedPreferences sp = getSharedPreferences(RevisorClima.PREFS, MODE_PRIVATE);
        if (sp.getBoolean("bateria_pedida", false)) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            sp.edit().putBoolean("bateria_pedida", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Funciones que la pantalla puede llamar (botones "Probar alarma", estado). */
    public static class Puente {
        private final Context ctx;
        private final WebView web;

        Puente(Context c, WebView w) { ctx = c.getApplicationContext(); web = w; }

        /** Pide la última medición real de la estación del aeropuerto de Guayaquil (SEGU). */
        @JavascriptInterface
        public void pedirMedicion() {
            new Thread(() -> {
                String js;
                try {
                    String cuerpo = RevisorClima.http(
                            "https://aviationweather.gov/api/data/metar?ids=SEGU&hours=3&format=raw");
                    js = "window.recibirMedicion && window.recibirMedicion(" + org.json.JSONObject.quote(cuerpo) + ")";
                } catch (Exception e) {
                    js = "window.recibirMedicion && window.recibirMedicion(null)";
                }
                final String codigo = js;
                web.post(() -> web.evaluateJavascript(codigo, null));
            }).start();
        }

        @JavascriptInterface
        public void probarAlarma() {
            Notificador.enviar(ctx, "Prueba de alarma",
                    "Si escuchas esto, las alarmas de clima de las plantas están activas.", 999);
        }

        @JavascriptInterface
        public boolean notificacionesActivas() {
            return Notificador.permitido(ctx);
        }

        @JavascriptInterface
        public String ultimaRevision() {
            SharedPreferences sp = ctx.getSharedPreferences(RevisorClima.PREFS, Context.MODE_PRIVATE);
            String err = sp.getString("ultimo_error", null);
            String t = sp.getString("ultima_revision", "");
            return err != null ? "error: " + err : t;
        }

        @JavascriptInterface
        public void abrirAjustes() {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); } catch (Exception ignored) { }
        }
    }
}
