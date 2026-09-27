# Clima Plantas: APK para Android

App para el celular con el clima de la **Planta Guayaquil** y la **Planta Lomas de Sargentillo**:
- Clima actual, lluvia de las próximas 2 h y 24 h, pronóstico de 7 días y precauciones.
- Mapa en vivo (radar de lluvia, satélite, tormentas) y enlaces a INAMHI y Gestión de Riesgos.
- **Alarmas integradas:** la app revisa el clima cada 15 minutos, aunque esté cerrada, y suena con sonido de alarma **solo** si hay aguacero inminente, lluvia muy fuerte, riesgo de inundación, calor o sol extremo.

No necesita ninguna otra app. Datos del clima: Open-Meteo (gratis).

---

## Cómo se genera la APK (una sola vez)

La APK se arma en GitHub, que la compila gratis y la deja lista para descargar.

1. Crea una cuenta gratis en **github.com**.
2. Crea un repositorio nuevo llamado `clima-plantas-apk`, **Public**.
3. Toca **uploading an existing file**, arrastra **todo** el contenido de esta carpeta (incluida la carpeta `.github`) y toca **Commit changes**.
4. Entra a **Actions**. Si te lo pide, habilita los flujos. El proceso **Construir APK** arranca solo y tarda unos 3 a 5 minutos. Cuando aparece el ✅ verde, la APK está lista.
5. El enlace de descarga queda así (cambia TU-USUARIO por tu usuario de GitHub):

   `https://github.com/TU-USUARIO/clima-plantas-apk/releases/latest/download/ClimaPlantas.apk`

   Ese enlace se puede mandar por WhatsApp al equipo.

---

## Cómo se instala en cada celular

1. Abre el enlace de descarga en el celular y descarga **ClimaPlantas.apk**.
2. Ábrela. Android preguntará si permites instalar apps de esta fuente: toca **Configuración → Permitir** y vuelve atrás.
3. Toca **Instalar**. Si aparece un aviso de Play Protect, toca **Instalar de todas formas** (sale porque la app no viene de la Play Store).
4. Abre **Clima Plantas** y:
   - **Permite las notificaciones** cuando lo pida.
   - Cuando pregunte por la batería, toca **Permitir**, para que el sistema no frene las revisiones.
5. Baja hasta **Alarmas en este celular** y toca **Probar alarma**. Debe sonar.

**Xiaomi, Redmi, Huawei, Oppo, Samsung:** estos celulares cierran apps en segundo plano para ahorrar batería. Entra a *Ajustes → Aplicaciones → Clima Plantas → Batería* y elige **Sin restricciones**. En Xiaomi activa también **Inicio automático**.

---

## Cuándo suena una alarma

| Alarma | Se activa si… |
|---|---|
| ⚡ Aguacero inminente | 5 mm o más en 15 minutos, **o** 10 mm o más en las próximas 2 horas (puede repetirse cada 3 horas) |
| 🌧️ Lluvia muy fuerte | 10 mm o más en una hora, **o** 20 mm o más en 24 h |
| 🌊 Riesgo de inundación | 50 mm o más en 24 h, **o** 80 mm o más en 3 días |
| 🔥 Calor extremo | Sensación térmica de 38 °C o más |
| ☀️ Sol extremo | Índice UV de 11 o más |

- Cada alarma suena **una vez por día y por planta** (el aguacero, hasta cada 3 horas).
- El amarillo **Precaución** de la pantalla (lluvia probable) no hace sonar alarma.
- El riesgo de inundación se estima por la lluvia pronosticada; confirma siempre con INAMHI y la Secretaría de Gestión de Riesgos.

## Cambiar umbrales o ubicaciones
Edita `app/src/main/assets/config.json` en GitHub (ícono del lápiz → **Commit changes**). GitHub vuelve a armar la APK sola en unos minutos; el equipo descarga la nueva desde el mismo enlace y se instala encima de la anterior.
