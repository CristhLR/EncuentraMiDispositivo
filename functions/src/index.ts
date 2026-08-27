import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { randomUUID } from "node:crypto";

initializeApp();

export const ringDevice = onCall(
  { region: "us-central1", timeoutSeconds: 15 },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Debes iniciar sesión.");
    }

    const deviceId = request.data?.deviceId;
    if (typeof deviceId !== "string" || deviceId.length < 4 || deviceId.length > 128) {
      throw new HttpsError("invalid-argument", "El identificador del dispositivo no es válido.");
    }

    const uid = request.auth.uid;
    const deviceRef = getFirestore()
      .collection("users")
      .doc(uid)
      .collection("devices")
      .doc(deviceId);
    const device = await deviceRef.get();

    if (!device.exists) {
      throw new HttpsError("not-found", "El dispositivo no pertenece a tu cuenta.");
    }

    const token = device.get("fcmToken");
    if (typeof token !== "string" || token.length === 0) {
      throw new HttpsError("failed-precondition", "El dispositivo todavía no puede recibir órdenes.");
    }

    const commandId = randomUUID();
    await getMessaging().send({
      token,
      data: {
        action: "RING",
        commandId,
      },
      android: {
        priority: "high",
        ttl: 60_000,
      },
    });

    return {
      accepted: true,
      commandId,
      acceptedAt: new Date().toISOString(),
    };
  },
);

