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

interface AuthUser {
  uid: string;
  email: string;
}

type FirestoreValue = {
  stringValue?: string;
  booleanValue?: boolean;
  timestampValue?: string;
};

interface FirestoreDocument {
  name?: string;
  fields?: Record<string, FirestoreValue>;
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
        return json({ ok: true, service: "encuentra-mi-dispositivo-api", groups: true });
      }
      if (request.method !== "POST") return json({ error: "Ruta no encontrada." }, 404);

      const token = readBearerToken(request.headers.get("Authorization"));
      const user = await verifyFirebaseUser(token, env.FIREBASE_WEB_API_KEY);
      const body = await readJson(request);
      const googleToken = await getGoogleAccessToken(env.FIREBASE_SERVICE_ACCOUNT);

      switch (url.pathname) {
        case "/state":
          return json(await readFamilyState(env, user, googleToken));
        case "/groups/create":
          return json(await createGroup(env, user, body, googleToken));
        case "/groups/join":
          return json(await joinGroup(env, user, body, googleToken));
        case "/devices/register":
          return json(await registerGroupDevice(env, user, body, googleToken));
        case "/ring":
          return json(await controlGroupDevice(env, user, body, googleToken, "RING"));
        case "/stop":
          return json(await controlGroupDevice(env, user, body, googleToken, "STOP"));
        default:
          return json({ error: "Ruta no encontrada." }, 404);
      }
    } catch (error) {
      const known = error instanceof HttpError ? error : undefined;
      if (!known) console.error("Unexpected worker error", error);
      return json(
        { error: known?.message ?? "No se pudo completar la solicitud." },
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
  if (!value?.startsWith("Bearer ")) throw new HttpError(401, "Debes iniciar sesión.");
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

async function verifyFirebaseUser(idToken: string, apiKey: string): Promise<AuthUser> {
  const response = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    },
  );
  if (!response.ok) throw new HttpError(401, "La sesión venció. Inicia sesión nuevamente.");
  const payload = (await response.json()) as {
    users?: Array<{ localId?: string; email?: string }>;
  };
  const found = payload.users?.[0];
  if (!found?.localId) throw new HttpError(401, "La sesión no es válida.");
  return { uid: found.localId, email: found.email ?? "Usuario familiar" };
}

async function createGroup(
  env: Env,
  user: AuthUser,
  body: Record<string, unknown>,
  token: string,
): Promise<unknown> {
  const name = typeof body.name === "string" ? body.name.trim() : "";
  if (name.length < 2 || name.length > 50) {
    throw new HttpError(400, "El nombre del grupo debe tener entre 2 y 50 caracteres.");
  }
  if (await readUserGroupId(env, user.uid, token)) {
    throw new HttpError(409, "Ya perteneces a un grupo familiar.");
  }

  const groupId = crypto.randomUUID();
  const inviteCode = await createUniqueInviteCode(env, token);
  const now = new Date().toISOString();
  await commitDocuments(env, [
    write(`groups/${groupId}`, {
      name: text(name), ownerUid: text(user.uid), inviteCode: text(inviteCode), createdAt: time(now),
    }),
    write(`groups/${groupId}/members/${user.uid}`, {
      email: text(user.email), role: text("owner"), joinedAt: time(now),
    }),
    write(`groupInvites/${inviteCode}`, {
      groupId: text(groupId), active: flag(true), createdAt: time(now),
    }),
    write(`users/${user.uid}`, { groupId: text(groupId), email: text(user.email) }),
  ], token);
  return { group: { id: groupId, name, inviteCode } };
}

async function joinGroup(
  env: Env,
  user: AuthUser,
  body: Record<string, unknown>,
  token: string,
): Promise<unknown> {
  const code = typeof body.code === "string" ? body.code.trim().toUpperCase() : "";
  if (!/^[A-Z2-9]{8}$/.test(code)) throw new HttpError(400, "El código familiar no es válido.");
  if (await readUserGroupId(env, user.uid, token)) {
    throw new HttpError(409, "Ya perteneces a un grupo familiar.");
  }

  const invite = await getDocument(env, `groupInvites/${code}`, token);
  const groupId = field(invite, "groupId");
  if (!groupId || invite?.fields?.active?.booleanValue !== true) {
    throw new HttpError(404, "No encontramos ese grupo familiar.");
  }
  const group = await getDocument(env, `groups/${groupId}`, token);
  if (!group) throw new HttpError(404, "El grupo familiar ya no existe.");

  const now = new Date().toISOString();
  await commitDocuments(env, [
    write(`groups/${groupId}/members/${user.uid}`, {
      email: text(user.email), role: text("member"), joinedAt: time(now),
    }),
    write(`users/${user.uid}`, { groupId: text(groupId), email: text(user.email) }),
  ], token);
  return {
    group: {
      id: groupId,
      name: field(group, "name") ?? "Grupo familiar",
      inviteCode: field(group, "inviteCode") ?? code,
    },
  };
}

async function registerGroupDevice(
  env: Env,
  user: AuthUser,
  body: Record<string, unknown>,
  token: string,
): Promise<unknown> {
  const deviceId = typeof body.deviceId === "string" ? body.deviceId : "";
  validateDeviceId(deviceId);
  const groupId = await readUserGroupId(env, user.uid, token);
  if (!groupId) return { registered: false, reason: "no-group" };
  await requireMember(env, groupId, user.uid, token);

  const privateDevice = await getDocument(env, `users/${user.uid}/devices/${deviceId}`, token);
  if (!privateDevice || !field(privateDevice, "fcmToken")) {
    throw new HttpError(409, "Este teléfono todavía no puede recibir órdenes.");
  }
  const groupDeviceId = `${user.uid}_${deviceId}`;
  await commitDocuments(env, [write(`groups/${groupId}/devices/${groupDeviceId}`, {
    ownerUid: text(user.uid),
    ownerEmail: text(user.email),
    deviceId: text(deviceId),
    name: text(field(privateDevice, "name") ?? "Dispositivo Android"),
    model: text(field(privateDevice, "model") ?? ""),
    platform: text(field(privateDevice, "platform") ?? "Android"),
    lastSeen: time(new Date().toISOString()),
  })], token);
  return { registered: true, groupId, groupDeviceId };
}

async function readFamilyState(env: Env, user: AuthUser, token: string): Promise<unknown> {
  const groupId = await readUserGroupId(env, user.uid, token);
  if (!groupId) return { group: null, devices: [] };
  await requireMember(env, groupId, user.uid, token);
  const group = await getDocument(env, `groups/${groupId}`, token);
  if (!group) throw new HttpError(404, "El grupo familiar ya no existe.");

  const documents = await listDocuments(env, `groups/${groupId}/devices`, token);
  const devices = documents.map((device) => ({
    id: device.name?.split("/").pop() ?? "",
    deviceId: field(device, "deviceId") ?? "",
    ownerUid: field(device, "ownerUid") ?? "",
    ownerEmail: field(device, "ownerEmail") ?? "",
    name: field(device, "name") ?? "Dispositivo Android",
    model: field(device, "model") ?? "",
    platform: field(device, "platform") ?? "Android",
    lastSeenMillis: Date.parse(device.fields?.lastSeen?.timestampValue ?? "") || 0,
  })).sort((a, b) => b.lastSeenMillis - a.lastSeenMillis);

  return {
    group: {
      id: groupId,
      name: field(group, "name") ?? "Grupo familiar",
      inviteCode: field(group, "inviteCode") ?? "",
    },
    devices,
  };
}

async function controlGroupDevice(
  env: Env,
  user: AuthUser,
  body: Record<string, unknown>,
  token: string,
  action: "RING" | "STOP",
): Promise<unknown> {
  const groupDeviceId = typeof body.deviceId === "string" ? body.deviceId : "";
  if (!/^[A-Za-z0-9._:-]{8,256}$/.test(groupDeviceId)) {
    throw new HttpError(400, "El dispositivo no es válido.");
  }
  const groupId = await readUserGroupId(env, user.uid, token);
  if (!groupId) throw new HttpError(409, "Primero debes unirte a un grupo familiar.");
  await requireMember(env, groupId, user.uid, token);

  const groupDevice = await getDocument(env, `groups/${groupId}/devices/${groupDeviceId}`, token);
  const ownerUid = field(groupDevice, "ownerUid");
  const deviceId = field(groupDevice, "deviceId");
  if (!ownerUid || !deviceId) {
    throw new HttpError(404, "El dispositivo no pertenece a tu grupo familiar.");
  }
  const privateDevice = await getDocument(env, `users/${ownerUid}/devices/${deviceId}`, token);
  const fcmToken = field(privateDevice, "fcmToken");
  if (!fcmToken) throw new HttpError(409, "El teléfono todavía no puede recibir órdenes.");
  await sendDeviceCommand(env.FIREBASE_PROJECT_ID, fcmToken, token, action);
  return { accepted: true, action };
}

async function readUserGroupId(env: Env, uid: string, token: string): Promise<string | null> {
  return field(await getDocument(env, `users/${uid}`, token), "groupId");
}

async function requireMember(env: Env, groupId: string, uid: string, token: string): Promise<void> {
  if (!(await getDocument(env, `groups/${groupId}/members/${uid}`, token))) {
    throw new HttpError(403, "No perteneces a ese grupo familiar.");
  }
}

async function createUniqueInviteCode(env: Env, token: string): Promise<string> {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const bytes = crypto.getRandomValues(new Uint8Array(8));
    let code = "";
    for (const byte of bytes) code += alphabet[byte % alphabet.length];
    if (!(await getDocument(env, `groupInvites/${code}`, token))) return code;
  }
  throw new HttpError(503, "No se pudo generar el código familiar. Inténtalo de nuevo.");
}

function validateDeviceId(deviceId: string): void {
  if (!/^[A-Za-z0-9._:-]{4,128}$/.test(deviceId)) {
    throw new HttpError(400, "El dispositivo no es válido.");
  }
}

async function getDocument(
  env: Env,
  path: string,
  token: string,
): Promise<FirestoreDocument | null> {
  const response = await fetch(documentUrl(env.FIREBASE_PROJECT_ID, path), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new HttpError(502, "No se pudo consultar la información familiar.");
  return (await response.json()) as FirestoreDocument;
}

async function listDocuments(
  env: Env,
  path: string,
  token: string,
): Promise<FirestoreDocument[]> {
  const response = await fetch(`${documentUrl(env.FIREBASE_PROJECT_ID, path)}?pageSize=100`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new HttpError(502, "No se pudieron consultar los dispositivos.");
  const payload = (await response.json()) as { documents?: FirestoreDocument[] };
  return payload.documents ?? [];
}

function documentUrl(projectId: string, path: string): string {
  const encoded = path.split("/").map(encodeURIComponent).join("/");
  return `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}` +
    `/databases/(default)/documents/${encoded}`;
}

function write(path: string, fields: Record<string, FirestoreValue>): unknown {
  return { path, fields };
}

async function commitDocuments(env: Env, writes: unknown[], token: string): Promise<void> {
  const root = `projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents/`;
  const body = {
    writes: writes.map((item) => {
      const value = item as { path: string; fields: Record<string, FirestoreValue> };
      return { update: { name: root + value.path, fields: value.fields } };
    }),
  };
  const response = await fetch(
    `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(env.FIREBASE_PROJECT_ID)}` +
      `/databases/(default)/documents:commit`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    },
  );
  if (!response.ok) {
    console.error("Firestore commit rejected", response.status, await response.text());
    throw new HttpError(502, "No se pudo guardar la información familiar.");
  }
}

function field(document: FirestoreDocument | null, name: string): string | null {
  return document?.fields?.[name]?.stringValue ?? null;
}

function text(value: string): FirestoreValue { return { stringValue: value }; }
function flag(value: boolean): FirestoreValue { return { booleanValue: value }; }
function time(value: string): FirestoreValue { return { timestampValue: value }; }

async function sendDeviceCommand(
  projectId: string,
  fcmToken: string,
  token: string,
  action: "RING" | "STOP",
): Promise<void> {
  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token: fcmToken,
          data: { action, commandId: crypto.randomUUID() },
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
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > now + 60) return cachedGoogleToken.value;

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
  const claims = base64Url(JSON.stringify({
    iss: account.client_email,
    sub: account.client_email,
    aud: account.token_uri ?? "https://oauth2.googleapis.com/token",
    scope: "https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${header}.${claims}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned),
  );
  const assertion = `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
  const response = await fetch(account.token_uri ?? "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new HttpError(502, "No se pudo autorizar el envío.");
  const payload = (await response.json()) as { access_token?: string; expires_in?: number };
  if (!payload.access_token) throw new HttpError(502, "Google no devolvió autorización.");
  cachedGoogleToken = {
    value: payload.access_token,
    expiresAt: now + (payload.expires_in ?? 3600),
  };
  return cachedGoogleToken.value;
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "").replace(/\s/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
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
