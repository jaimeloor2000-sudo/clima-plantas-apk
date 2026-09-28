package ec.planta.climaplantas;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/** Muestra las alarmas con sonido de alarma y vibración. */
public final class Notificador {

    static final String CANAL = "alertas_clima";
    /** Canal con la sirena propia de la app: lluvia, aguacero e inundación. */
    static final String CANAL_SIRENA = "alertas_sirena";

    private Notificador() {}

    static void crearCanal(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Alertas de clima", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Sol extremo en las plantas");
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
        ch.enableLights(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    static void crearCanalSirena(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL_SIRENA) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL_SIRENA, "Alarma de lluvia e inundación",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Tono de carillón que se repite hasta tocar la notificación: aguacero, lluvia muy fuerte o riesgo de inundación");
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        Uri sirena = Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.sirena);
        ch.setSound(sirena, aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000, 500, 1000});
        ch.enableLights(true);
        ch.setLightColor(0xFFB42A24);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    static boolean permitido(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void enviar(Context c, String titulo, String texto, int id) {
        enviar(c, titulo, texto, id, false);
    }

    /** @param sirena true = sirena propia que se repite hasta que toquen la notificación */
    static void enviar(Context c, String titulo, String texto, int id, boolean sirena) {
        crearCanal(c);
        crearCanalSirena(c);
        if (!permitido(c)) return;
        Intent abrir = new Intent(c, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, abrir,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String primeraLinea = texto.contains("\n") ? texto.substring(0, texto.indexOf('\n')) : texto;
        Notification n = new Notification.Builder(c, sirena ? CANAL_SIRENA : CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF0A6E8A)
                .setContentTitle(titulo)
                .setContentText(primeraLinea)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        if (sirena) n.flags |= Notification.FLAG_INSISTENT; // repite el sonido hasta que la toquen
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, n);
    }
}
