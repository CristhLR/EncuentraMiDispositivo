interface Env {
  FIREBASE_PROJECT_ID: string;
  FIREBASE_WEB_API_KEY: string;
  FIREBASE_SERVICE_ACCOUNT: string;
}

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

interface CachedToken {
  value: string;
  expiresAt: number;
}

let cachedGoogleToken: CachedToken | undefined;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);

      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true, service: "encuentra-mi-dispositivo-api" });
      }

      if (request.method !== "POST" || url.pathname !== "/ring") {
        return json({ error: "Ruta no encontrada." }, 404);
      }

      const idToken = readBearerToken(request.headers.get("Authorization"));
      const uid = await verifyFirebaseUser(idToken, env.FIREBASE_WEB_API_KEY);
      const body = await readJson(request);
      const deviceId = body.deviceId;

      if (typeof deviceId !== "string" || !/^[A-Za-z0-9._:-]{4,128}$/.test(deviceId)) {
        return json({ error: "El dispositivo no es válido." }, 400);
      }

      const accessToken = await getGoogleAccessToken(env.FIREBASE_SERVICE_ACCOUNT);
      const fcmToken = await readOwnedDeviceToken(
        env.FIREBASE_PROJECT_ID,
        uid,
        deviceId,
        accessToken,
      );

      await sendRingCommand(env.FIREBASE_PROJECT_ID, fcmToken, accessToken);
      return json({ accepted: true });
    } catch (error) {
      const known = error instanceof HttpError ? error : undefined;
      if (!known) console.error("Unexpected worker error", error);
      return json(
        { error: known?.message ?? "No se pudo enviar la alarma." },
        known?.status ?? 500,
      );
    }
  },
};

class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function readBearerToken(value: string | null): string {
  if (!value?.startsWith("Bearer ")) {
    throw new HttpError(401, "Debes iniciar sesión.");
  }
  const token = value.slice("Bearer ".length).trim();
  if (!token) throw new HttpError(401, "La sesión no es válida.");
  return token;
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  try {
    const value = await request.json();
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
    return value as Record<string, unknown>;
  } catch {
    throw new HttpError(400, "La solicitud no es válida.");
  }
}

async function verifyFirebaseUser(idToken: string, apiKey: string): Promise<string> {
  const response = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    },
  );

  if (!response.ok) throw new HttpError(401, "La sesión venció. Inicia sesión nuevamente.");
  const payload = (await response.json()) as { users?: Array<{ localId?: string }> };
  const uid = payload.users?.[0]?.localId;
  if (!uid) throw new HttpError(401, "La sesión no es válida.");
  return uid;
}

async function readOwnedDeviceToken(
  projectId: string,
  uid: string,
  deviceId: string,
  accessToken: string,
): Promise<string> {
  const documentUrl =
    `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}` +
    `/databases/(default)/documents/users/${encodeURIComponent(uid)}` +
    `/devices/${encodeURIComponent(deviceId)}`;
  const response = await fetch(documentUrl, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });

  if (response.status === 404) {
    throw new HttpError(404, "El dispositivo no pertenece a tu cuenta.");
  }
  if (!response.ok) throw new HttpError(502, "No se pudo consultar el dispositivo.");

  const document = (await response.json()) as {
    fields?: { fcmToken?: { stringValue?: string } };
  };
  const token = document.fields?.fcmToken?.stringValue;
  if (!token) throw new HttpError(409, "El dispositivo todavía no puede recibir órdenes.");
  return token;
}

async function sendRingCommand(
  projectId: string,
  fcmToken: string,
  accessToken: string,
): Promise<void> {
  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token: fcmToken,
          data: { action: "RING", commandId: crypto.randomUUID() },
          android: { priority: "HIGH", ttl: "60s" },
        },
      }),
    },
  );

  if (!response.ok) {
    console.error("FCM rejected request", response.status, await response.text());
    throw new HttpError(502, "El teléfono no pudo recibir la orden.");
  }
}

async function getGoogleAccessToken(serviceAccountJson: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > now + 60) {
    return cachedGoogleToken.value;
  }

  let account: ServiceAccount;
  try {
    account = JSON.parse(serviceAccountJson) as ServiceAccount;
  } catch {
    throw new HttpError(500, "El servidor no tiene credenciales válidas.");
  }

  if (!account.client_email || !account.private_key) {
    throw new HttpError(500, "El servidor no tiene credenciales completas.");
  }

  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(
    JSON.stringify({
      iss: account.client_email,
      sub: account.client_email,
      aud: account.token_uri ?? "https://oauth2.googleapis.com/token",
      scope:
        "https://www.googleapis.com/auth/firebase.messaging " +
        "https://www.googleapis.com/auth/datastore",
      iat: now,
      exp: now + 3600,
    }),
  );
  const unsignedJwt = `${header}.${claims}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsignedJwt),
  );
  const assertion = `${unsignedJwt}.${base64UrlBytes(new Uint8Array(signature))}`;

  const response = await fetch(account.token_uri ?? "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new HttpError(502, "No se pudo autorizar el envío.");

  const tokenPayload = (await response.json()) as { access_token?: string; expires_in?: number };
  if (!tokenPayload.access_token) throw new HttpError(502, "Google no devolvió autorización.");
  cachedGoogleToken = {
    value: tokenPayload.access_token,
    expiresAt: now + (tokenPayload.expires_in ?? 3600),
  };
  return cachedGoogleToken.value;
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const base64 = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer;
}

function base64Url(value: string): string {
  return base64UrlBytes(new TextEncoder().encode(value));
}

function base64UrlBytes(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

