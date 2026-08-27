# Encuentra mi dispositivo

Aplicación Android para conectar teléfonos propios o familiares mediante cuentas individuales y enviarles una orden remota para que reproduzcan una alarma al volumen máximo disponible.

## Estado del proyecto

Este repositorio contiene un MVP funcional con Firebase y Cloudflare Workers:

- inicio de sesión y creación de cuenta con correo y contraseña;
- creación de grupos familiares y unión mediante un código de ocho caracteres;
- registro automático del teléfono y de su token de Firebase Cloud Messaging (FCM);
- lista compartida de dispositivos para los miembros autenticados del grupo;
- orden remota protegida por un Cloudflare Worker;
- alarma en primer plano, con volumen de alarma máximo, notificación para detenerla y cierre automático después de cinco minutos;
- reglas de Firestore que impiden leer o modificar dispositivos de otra cuenta.

La aplicación **no rastrea la ubicación GPS** en esta primera versión. Su objetivo inicial es encontrar un equipo cercano haciéndolo sonar.

## Arquitectura

1. Cada persona crea su propia cuenta y registra su teléfono en `users/{uid}/devices/{deviceId}`.
2. Una persona crea el grupo familiar y comparte el código; las demás se unen con sus propias cuentas.
3. Al pulsar **Hacer sonar**, la app envía el identificador del equipo y el token de sesión al endpoint `/ring` del Worker.
4. El Worker valida que quien solicita la alarma y el teléfono pertenezcan al mismo grupo.
5. Firebase Cloud Messaging entrega una orden de datos de alta prioridad.
6. El teléfono objetivo inicia un servicio visible y reproduce la alarma.

No se envía el token FCM desde el cliente que solicita la alarma; el Worker lo lee del documento protegido del usuario. Esto evita usar el servicio para activar dispositivos ajenos.

## Configuración

### 1. Firebase

1. Crea un proyecto en [Firebase Console](https://console.firebase.google.com/).
2. Agrega una aplicación Android con el paquete:

   ```text
   com.cristhlr.encuentramidispositivo
   ```

3. Descarga `google-services.json` y colócalo en `app/google-services.json`.
4. En **Authentication > Sign-in method**, habilita **Email/Password**.
5. Crea una base de datos de Cloud Firestore.

`google-services.json` está excluido de Git porque identifica tu proyecto Firebase. No subas cuentas de servicio ni claves privadas al repositorio.

### 2. Desplegar reglas de Firestore

Publica el contenido de `firestore.rules` desde la pestaña **Reglas** de Firestore o mediante Firebase CLI:

```bash
firebase login
firebase use --add
firebase deploy --only firestore:rules
```

### 3. Desplegar el backend gratuito

Conecta el repositorio a Cloudflare Workers y configura:

```text
Root directory: /worker
Build command: npm run check
Deploy command: npm run deploy
```

Agrega estas variables en **Settings > Runtime variables and secrets**:

- `FIREBASE_PROJECT_ID`: identificador del proyecto, como texto.
- `FIREBASE_WEB_API_KEY`: clave web de Firebase, como secreto.
- `FIREBASE_SERVICE_ACCOUNT`: contenido completo del JSON de la cuenta de servicio, como secreto.

La aplicación usa actualmente `https://encuentra-mi-dispositivo-api.cdavidleonr.workers.dev/ring`. Esta arquitectura funciona con Firebase Spark y el plan gratuito de Cloudflare dentro de sus respectivos límites.

### 4. Ejecutar Android

1. Abre la raíz del repositorio con Android Studio.
2. Espera la sincronización de Gradle.
3. Ejecuta la app en dos teléfonos Android con servicios de Google Play.
4. Acepta el permiso de notificaciones.
5. Usa una cuenta diferente en cada persona.
6. Crea un grupo familiar en un equipo y comparte el código con los demás.
7. Cuando todos se unan, pulsa **Hacer sonar** sobre cualquier dispositivo del grupo.

## Limitaciones reales de Android

- El teléfono debe estar encendido y conectado a Internet.
- La app debe haberse instalado, abierto e iniciado sesión antes de perder el equipo.
- Si el usuario fuerza la detención de la app, Android puede bloquear FCM hasta volver a abrirla.
- Algunos fabricantes retrasan mensajes por ahorro extremo de batería.
- La app intenta subir el canal de alarma al máximo, pero Android, el modo No molestar, controles empresariales y personalizaciones del fabricante pueden impedirlo.
- La notificación permite detener la alarma y el servicio se detiene automáticamente después de cinco minutos.

Por estas restricciones, ninguna aplicación común puede prometer que sonará siempre en absolutamente todos los teléfonos y estados del sistema.

## Próximas mejoras

- verificación de correo y recuperación de contraseña;
- renombrar y eliminar dispositivos;
- indicador de conexión actualizado periódicamente;
- Firebase App Check y control de frecuencia para las órdenes;
- ubicación cifrada y mapa, con consentimiento explícito;
- pruebas instrumentadas y canal interno de distribución.

## Uso responsable

El proyecto está diseñado únicamente para dispositivos propios o familiares registrados voluntariamente en un grupo compartido. No debe adaptarse para vigilar personas, ocultar su funcionamiento ni controlar equipos sin permiso.
