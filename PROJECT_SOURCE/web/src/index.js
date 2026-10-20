import { createPayMongoCheckout, handlePayMongoWebhook } from "./paymongo.js";

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": "*",
      "access-control-allow-headers": "content-type,x-parent-token,x-child-token,x-admin-key,paymongo-signature,x-paymongo-signature",
      "access-control-allow-methods": "GET,POST,OPTIONS"
    }
  });

const now = () => Date.now();

const iso = (ms) => ms ? new Date(ms).toISOString() : null;

function makeId(prefix) {
  return `${prefix}-${crypto.randomUUID().replaceAll("-", "").slice(0, 8).toUpperCase()}`;
}

function makeToken(prefix) {
  return `${prefix}_${crypto.randomUUID().replaceAll("-", "")}`;
}

function randomCode() {
  const a = new Uint32Array(1);
  crypto.getRandomValues(a);
  return String(100000 + (a[0] % 900000));
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(String(value));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)]
    .map(x => x.toString(16).padStart(2, "0"))
    .join("");
}

async function hashPassword(password, salt) {
  return sha256(`${salt}:${password}`);
}

async function body(request) {
  try {
    return await request.json();
  } catch {
    return {};
  }
}

async function setting(env, key, fallback = "") {
  const row = await env.DB
    .prepare("SELECT value FROM settings WHERE key = ?")
    .bind(key)
    .first();

  return row?.value ?? fallback;
}

async function allSettings(env) {
  const rows = await env.DB
    .prepare("SELECT key,value FROM settings")
    .all();

  const out = {};
  for (const row of rows.results ?? []) out[row.key] = row.value;
  return out;
}

async function parentByToken(env, token) {
  if (!token) return null;

  return await env.DB
    .prepare("SELECT * FROM parents WHERE token = ?")
    .bind(token)
    .first();
}

async function childByToken(env, token) {
  if (!token) return null;

  return await env.DB
    .prepare("SELECT * FROM children WHERE token = ?")
    .bind(token)
    .first();
}

async function parentStatus(env, p) {
  const t = now();

  if (p.subscription_status === "TRIAL") {
    if (p.trial_ends_at && t >= p.trial_ends_at) {
      return "EXPIRED";
    }

    return "TRIAL";
  }

  if (
    p.subscription_status === "ACTIVE" ||
    p.subscription_status === "NEAR_EXPIRY"
  ) {
    if (p.subscription_ends_at && t >= p.subscription_ends_at) {
      return "EXPIRED";
    }

    if (
      p.subscription_ends_at &&
      p.subscription_ends_at - t <= 7 * 24 * 60 * 60 * 1000
    ) {
      return "NEAR_EXPIRY";
    }

    return "ACTIVE";
  }

  return p.subscription_status || "PENDING_ACTIVATION";
}

async function parentSummary(env, p, request) {
  const s = await allSettings(env);
  const billing = await billingSummaryForParent(env, p);
  let status = await parentStatus(env, p);
  if (billing.childCount === 0) status = "PENDING_CHILD";
  else if (billing.trialActive) status = "TRIAL";
  else if (billing.activeCount > 0) status = billing.totalDue > 0 ? "PARTIAL_PAYMENT" : "ACTIVE";
  else if (billing.totalDue > 0) status = "EXPIRED";

  return {
    parentId: p.parent_id,
    name: p.name,
    email: p.email,
    mobile: p.mobile,
    status,

    trialEndsAt: iso(p.trial_ends_at),
    subscriptionEndsAt: iso(p.subscription_ends_at),

    trialDays: 1,
    initialPayment: Number(s.initial_payment || 300),
    registrationFee: Number(s.registration_fee || 100),
    annualFee: Number(s.annual_fee || 300),
    renewalFee: Number(s.renewal_fee || 300),
    billing,

    paymentQrUrl:
      new URL(String(s.payment_qr || "/payment-qr.png"), request.url).toString()
  };
}

function childSummary(c) {
  const online =
    Boolean(c.last_seen_at) &&
    now() - Number(c.last_seen_at) <= 45 * 1000;

  return {
    childId: c.child_id,
    code: c.code,
    name: c.name,
    paired: Boolean(c.parent_id),
    online,
    lastSeenAt: iso(c.last_seen_at),
    locked: Boolean(c.locked)
  };
}

async function requireParent(env, request) {
  const token = request.headers.get("x-parent-token");
  const p = await parentByToken(env, token);

  if (!p) {
    throw new Response(
      JSON.stringify({ error: "Invalid Parent session" }),
      {
        status: 401,
        headers: {
          "content-type": "application/json",
          "access-control-allow-origin": "*"
        }
      }
    );
  }

  return p;
}

async function requireChild(env, request) {
  const token = request.headers.get("x-child-token");
  const c = await childByToken(env, token);

  if (!c) {
    throw new Response(
      JSON.stringify({ error: "Invalid Child session" }),
      {
        status: 401,
        headers: {
          "content-type": "application/json",
          "access-control-allow-origin": "*"
        }
      }
    );
  }

  return c;
}

async function generateUniqueChildCode(env) {
  for (let i = 0; i < 50; i++) {
    const code = randomCode();

    const exists = await env.DB
      .prepare("SELECT child_id FROM children WHERE code = ?")
      .bind(code)
      .first();

    if (!exists) return code;
  }

  throw new Error("Unable to generate unique Child code");
}

const DAY_MS = 24 * 60 * 60 * 1000;
const CHILD_SECOND_FEE = 300;
const CHILD_EXTRA_FEE = 50;
const CHILD_FIRST_EXPIRED_FEE = 300;
const CHILD_FIRST_DISCOUNTED_FEE = 100;

function initialChildTrialWindow(at = now()) {
  return { trialStartedAt: at, trialEndsAt: at + DAY_MS };
}

function effectiveBillingStatus(row, at = now()) {
  const status = String(row.billing_status || "PENDING_PAYMENT").toUpperCase();
  if (status === "TRIAL" && Number(row.trial_ends_at || 0) > 0 && at >= Number(row.trial_ends_at)) return "EXPIRED";
  if (status === "ACTIVE" && Number(row.paid_until || 0) > 0 && at >= Number(row.paid_until)) return "EXPIRED";
  return status;
}

function billingAmountDue(row, allRows, at = now()) {
  const status = effectiveBillingStatus(row, at);
  if (status === "TRIAL" || (status === "ACTIVE" && Number(row.paid_until || 0) > at)) return 0;
  const order = Number(row.child_order || 0);
  if (order === 1) {
    const firstTrialEnd = Number(row.trial_ends_at || 0);
    const secondPaidInTrial = allRows.some(other =>
      Number(other.child_order || 0) === 2 &&
      Number(other.paid_at || 0) > 0 &&
      firstTrialEnd > 0 &&
      Number(other.paid_at) < firstTrialEnd
    );
    if (status === "EXPIRED" || status === "PENDING_PAYMENT") {
      if (Number(row.paid_until || 0) > 0 && Number(row.paid_until) <= at) {
        return Number(row.paid_amount || (secondPaidInTrial ? CHILD_FIRST_DISCOUNTED_FEE : CHILD_FIRST_EXPIRED_FEE));
      }
      return secondPaidInTrial ? CHILD_FIRST_DISCOUNTED_FEE : CHILD_FIRST_EXPIRED_FEE;
    }
    return 0;
  }
  if (status === "EXPIRED" && Number(row.paid_until || 0) > 0) {
    return Number(row.paid_amount || (order === 2 ? CHILD_SECOND_FEE : CHILD_EXTRA_FEE));
  }
  if (status === "PENDING_PAYMENT" || status === "EXPIRED") {
    return order === 2 ? CHILD_SECOND_FEE : CHILD_EXTRA_FEE;
  }
  return 0;
}

function lockBillingDecision(row, allRows, at = now()) {
  if (!row) {
    return { allowed: false, reason: "BILLING_SYNC_REQUIRED", amountDue: 0, status: "UNKNOWN" };
  }

  const status = effectiveBillingStatus(row, at);
  const amountDue = billingAmountDue(row, allRows, at);

  if (amountDue > 0 || status === "EXPIRED" || status === "PENDING_PAYMENT") {
    return { allowed: false, reason: "PAYMENT_REQUIRED", amountDue, status };
  }

  return { allowed: true, reason: "", amountDue, status };
}
function billingChildView(row, allRows, at = now()) {
  const status = effectiveBillingStatus(row, at);
  const amountDue = billingAmountDue(row, allRows, at);
  let reason = "";
  if (amountDue > 0) {
    if (Number(row.child_order) === 1) {
      reason = amountDue === CHILD_FIRST_DISCOUNTED_FEE
        ? "FIRST_CHILD_AFTER_SECOND_CHILD_PAID_DURING_TRIAL"
        : (Number(row.paid_until || 0) > 0 ? "FIRST_CHILD_RENEWAL" : "FIRST_CHILD_AFTER_TRIAL");
    } else if (Number(row.child_order) === 2) {
      reason = Number(row.paid_until || 0) > 0 ? "SECOND_CHILD_RENEWAL" : "SECOND_CHILD_ACTIVATION";
    } else {
      reason = Number(row.paid_until || 0) > 0 ? "ADDITIONAL_CHILD_RENEWAL" : "ADDITIONAL_CHILD_ACTIVATION";
    }
  }
  return {
    billingChildId: row.billing_child_id,
    childKey: row.child_key,
    childCode: row.child_code,
    name: row.name,
    childOrder: Number(row.child_order || 0),
    billingStatus: status,
    pairedAt: iso(row.paired_at),
    trialStartedAt: iso(row.trial_started_at),
    trialEndsAt: iso(row.trial_ends_at),
    paidUntil: iso(row.paid_until),
    paidAt: iso(row.paid_at),
    paidAmount: Number(row.paid_amount || 0),
    amountDue,
    billingReason: reason
  };
}

async function billingRowsForParent(env, parentId) {
  const rows = await env.DB.prepare(
    "SELECT * FROM billing_children WHERE parent_id = ? ORDER BY child_order ASC, created_at ASC"
  ).bind(parentId).all();
  return rows.results || [];
}

async function billingSummaryForParent(env, parent) {
  const rows = await billingRowsForParent(env, parent.parent_id);
  const at = now();
  const children = rows.map(row => billingChildView(row, rows, at));
  const dueChildren = children.filter(child => child.amountDue > 0);
  const nextDue = dueChildren[0] || null;
  const activeCount = children.filter(child => child.billingStatus === "ACTIVE").length;
  const trialChild = children.find(child => child.billingStatus === "TRIAL");
  return {
    childCount: children.length,
    activeCount,
    trialActive: Boolean(trialChild),
    trialChild: trialChild,
    totalDue: dueChildren.reduce((sum, child) => sum + Number(child.amountDue || 0), 0),
    nextDue,
    children
  };
}

async function registerBillingChild(env, parent, input) {
  const code = String(input.code || "").trim();
  const childKey = String(input.childKey || "").trim().slice(0, 128);
  const name = String(input.name || "Child").trim().slice(0, 80) || "Child";
  if (!/^\d{6}$/.test(code) || !childKey) {
    throw new Response(JSON.stringify({ error: "A stable Child key and 6-digit Child code are required" }), {
      status: 400,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }

  const existing = await env.DB.prepare(
    "SELECT * FROM billing_children WHERE parent_id = ? AND child_code = ?"
  ).bind(parent.parent_id, code).first();
  if (existing) {
    if (existing.child_key !== childKey || existing.name !== name) {
      await env.DB.prepare(
        "UPDATE billing_children SET child_key = ?, name = ?, updated_at = ? WHERE billing_child_id = ?"
      ).bind(childKey, name, now(), existing.billing_child_id).run();
    }
    const rows = await billingRowsForParent(env, parent.parent_id);
    const fresh = rows.find(row => row.billing_child_id === existing.billing_child_id) || existing;
    return billingChildView(fresh, rows);
  }

  const rows = await billingRowsForParent(env, parent.parent_id);
  const at = now();
  const suppliedPairAt = Number(input.pairedAt || 0);
  // Preserve the real local pairing time if the Parent was briefly offline, but
  // reject future/ancient client timestamps that could create a false trial.
  const pairedAt = suppliedPairAt > 0 && suppliedPairAt <= at + 5 * 60 * 1000 && suppliedPairAt >= at - 30 * DAY_MS
    ? Math.min(suppliedPairAt, at) : at;
  const order = rows.length + 1;
  let status = "PENDING_PAYMENT";
  let trialStartedAt = null;
  let trialEndsAt = null;
  let paidUntil = null;
  let paidAt = null;
  let paidAmount = 0;

  const legacySubscriptionEndsAt = Number(parent.subscription_ends_at || 0);
  const legacySubscriptionActive =
    ["ACTIVE", "NEAR_EXPIRY"].includes(String(parent.subscription_status || "").toUpperCase()) &&
    legacySubscriptionEndsAt > at;

  if (order === 1 && legacySubscriptionActive) {
    // Preserve a currently paid legacy account when it first enters the per-Child ledger.
    status = "ACTIVE";
    paidUntil = legacySubscriptionEndsAt;
    paidAt = Number(parent.activated_at || at);
    paidAmount = 300;
  } else if (order === 1) {
    // First Child's free 24 hours starts at successful pairing, never at account creation/login.
    const trialWindow = initialChildTrialWindow(at);
    trialStartedAt = trialWindow.trialStartedAt;
    trialEndsAt = trialWindow.trialEndsAt;
    status = "TRIAL";
  }

  const billingChildId = makeId("BC");
  await env.DB.prepare(`
    INSERT INTO billing_children
      (billing_child_id, parent_id, child_key, child_code, name, child_order,
       billing_status, paired_at, trial_started_at, trial_ends_at, paid_until,
       paid_at, paid_amount, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).bind(
    billingChildId, parent.parent_id, childKey, code, name, order, status,
    pairedAt, trialStartedAt, trialEndsAt, paidUntil, paidAt, paidAmount, at, at
  ).run();

  if (order === 1) {
    await env.DB.prepare(`
      UPDATE parents
      SET activated_at = ?, trial_ends_at = ?, subscription_status = 'TRIAL'
      WHERE parent_id = ? AND (subscription_ends_at IS NULL OR subscription_ends_at <= ?)
    `).bind(trialStartedAt, trialEndsAt, parent.parent_id, at).run();
  }

  const newRows = await billingRowsForParent(env, parent.parent_id);
  const row = newRows.find(item => item.billing_child_id === billingChildId);
  return billingChildView(row, newRows, at);
}


function parsePaymentProof(env, input) {
  if (!env.PAYMENT_PROOFS) {
    throw new Response(JSON.stringify({ error: "Private proof storage is not configured for this environment." }), {
      status: 503,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }
  const raw = String(input.proofBase64 || "").replace(/^data:[^;]+;base64,/i, "").replace(/\s+/g, "");
  if (!raw || raw.length > 7_200_000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(raw)) {
    throw new Response(JSON.stringify({ error: "Select a valid proof image up to 5 MB." }), {
      status: 400,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }
  let binary;
  try { binary = atob(raw); } catch {
    throw new Response(JSON.stringify({ error: "Payment proof image is not valid Base64." }), {
      status: 400,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }
  const size = binary.length;
  if (size < 1 || size > 5 * 1024 * 1024) {
    throw new Response(JSON.stringify({ error: "Proof image must not exceed 5 MB." }), {
      status: 413,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }
  const bytes = new Uint8Array(size);
  for (let i = 0; i < size; i++) bytes[i] = binary.charCodeAt(i);
  let detected = "";
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) detected = "image/jpeg";
  else if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 && bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a) detected = "image/png";
  else if (bytes.length >= 12 && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" && String.fromCharCode(...bytes.slice(8, 12)) === "WEBP") detected = "image/webp";
  if (!detected) {
    throw new Response(JSON.stringify({ error: "Only real JPEG, PNG, or WEBP images are accepted as payment proof." }), {
      status: 415,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }
  const declared = String(input.proofMimeType || "").trim().toLowerCase();
  if (declared && declared !== detected) {
    throw new Response(JSON.stringify({ error: "Proof image type does not match its actual file content." }), {
      status: 415,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }
  const proofName = String(input.proofName || `payment-proof.${detected === "image/jpeg" ? "jpg" : detected.split("/")[1]}`)
    .replace(/[\\/\x00-\x1f]/g, "_").slice(0, 120);
  const reference = String(input.reference || input.referenceNumber || "").trim().slice(0, 120);
  if (reference.length < 4) {
    throw new Response(JSON.stringify({ error: "Enter a transaction/reference number of at least 4 characters." }), {
      status: 400,
      headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" }
    });
  }
  return { bytes, size, contentType: detected, proofName, reference };
}

async function checkDuplicatePaymentReference(env, reference, exceptPaymentId = "") {
  const normalized = String(reference || "").replace(/\s+/g, "").toUpperCase();
  return await env.DB.prepare(`
    SELECT payment_id FROM payments
    WHERE UPPER(REPLACE(COALESCE(reference_number, ''), ' ', '')) = ?
      AND payment_id <> ? AND status IN ('PENDING', 'APPROVED')
    LIMIT 1
  `).bind(normalized, exceptPaymentId).first();
}

async function storePaymentProof(env, paymentId, parentId, proof) {
  const extension = proof.contentType === "image/jpeg" ? "jpg" : proof.contentType.split("/")[1];
  const key = `payment-proofs/${parentId}/${paymentId}/${crypto.randomUUID()}.${extension}`;
  await env.PAYMENT_PROOFS.put(key, proof.bytes, {
    httpMetadata: { contentType: proof.contentType, cacheControl: "private, no-store" },
    customMetadata: { paymentId: String(paymentId), parentId: String(parentId) }
  });
  return key;
}

function adminKeyAuthorized(request, env) {
  const supplied = request.headers.get("x-admin-key") || "";
  const expected = String(env.ADMIN_KEY || "");
  if (!expected || !supplied || supplied.length !== expected.length) return false;
  let mismatch = 0;
  for (let i = 0; i < expected.length; i++) mismatch |= supplied.charCodeAt(i) ^ expected.charCodeAt(i);
  return mismatch === 0;
}

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method.toUpperCase();

  // Provider webhook: verify the raw signed request before parsing or changing any billing record.
  if (path === "/api/paymongo/webhook" && method === "POST") {
    return handlePayMongoWebhook(request, env);
  }

  if (method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-headers": "content-type,x-parent-token,x-child-token,x-admin-key,paymongo-signature,x-paymongo-signature",
        "access-control-allow-methods": "GET,POST,OPTIONS"
      }
    });
  }

  if (path === "/api/health" && method === "GET") {
    return json({
      ok: true,
      service: "bedtime-parental",
      mode: "worker"
    });
  }

  if (path === "/health" && method === "GET") {
    return json({
      ok: true,
      service: "bedtime-parental",
      database: "D1"
    });
  }

  if (path === "/api/register-parent" && method === "POST") {
    const b = await body(request);

    const name = String(b.name || "").trim();
    const email = String(b.email || "").trim().toLowerCase();
    const mobile = String(b.mobile || "").trim();
    const password = String(b.password || "");

    if (!name || !email || !password) {
      return json(
        { error: "Name, email, and password are required" },
        400
      );
    }

    const existing = await env.DB
      .prepare("SELECT parent_id FROM parents WHERE email = ?")
      .bind(email)
      .first();

    if (existing) {
      return json(
        { error: "Email already registered" },
        409
      );
    }

    const parentId = makeId("P");
    const salt = crypto.randomUUID();
    const passwordHash = await hashPassword(password, salt);
    const recoveryCode = crypto.randomUUID().replaceAll("-", "").slice(0, 12).toUpperCase();
    const recoveryCodeHash = await sha256(recoveryCode);
    const createdAt = now();

    await env.DB
      .prepare(`
        INSERT INTO parents
        (
          parent_id, name, email, mobile, password_hash, password_salt,
          token, activated_at, trial_ends_at, subscription_status,
          subscription_ends_at, recovery_code_hash, created_at
        )
        VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 'PENDING_ACTIVATION', NULL, ?, ?)
      `)
      .bind(parentId, name, email, mobile, passwordHash, salt, recoveryCodeHash, createdAt)
      .run();

    return json({
      parent: {
        parentId,
        name,
        email,
        status: "PENDING_ACTIVATION",
        recoveryCode
      }
    });
  }

  if (path === "/api/parent/recovery-code" && method === "GET") {
    const p = await requireParent(env, request);
    // Only a live authenticated session may rotate and view a recovery code.
    const recoveryCode = crypto.randomUUID().replaceAll("-", "").slice(0, 12).toUpperCase();
    const recoveryCodeHash = await sha256(recoveryCode);
    await env.DB.prepare("UPDATE parents SET recovery_code_hash = ? WHERE parent_id = ?")
      .bind(recoveryCodeHash, p.parent_id).run();
    return json({ recoveryCode });
  }

  if (path === "/api/parent/forgot-password" && method === "POST") {
    const b = await body(request);
    const email = String(b.email || "").trim().toLowerCase();
    const mobile = String(b.mobile || "").trim();
    const recoveryCode = String(b.recoveryCode || "").trim().toUpperCase();
    const newPassword = String(b.newPassword || "");
    if (!email || !mobile || !recoveryCode || newPassword.length < 8) {
      return json({ error: "Email, mobile, recovery code and a password of at least 8 characters are required" }, 400);
    }
    const p = await env.DB.prepare("SELECT * FROM parents WHERE email = ? AND mobile = ?")
      .bind(email, mobile).first();
    if (!p || !p.recovery_code_hash || await sha256(recoveryCode) !== p.recovery_code_hash) {
      return json({ error: "Recovery details do not match" }, 401);
    }
    const salt = crypto.randomUUID();
    const passwordHash = await hashPassword(newPassword, salt);
    const recoveryCodeNext = crypto.randomUUID().replaceAll("-", "").slice(0, 12).toUpperCase();
    const recoveryHashNext = await sha256(recoveryCodeNext);
    await env.DB.prepare(`UPDATE parents SET password_hash = ?, password_salt = ?, token = NULL, recovery_code_hash = ? WHERE parent_id = ?`)
      .bind(passwordHash, salt, recoveryHashNext, p.parent_id).run();
    return json({ ok: true, message: "Password reset. Sign in again and save the newly issued recovery code from Parent Settings." });
  }

  if (path === "/api/parent/change-password" && method === "POST") {
    const p = await requireParent(env, request);
    const b = await body(request);
    const currentPassword = String(b.currentPassword || "");
    const newPassword = String(b.newPassword || "");
    if (newPassword.length < 8) return json({ error: "New password must be at least 8 characters" }, 400);
    if (await hashPassword(currentPassword, p.password_salt) !== p.password_hash) {
      return json({ error: "Current password is incorrect" }, 401);
    }
    const salt = crypto.randomUUID();
    const passwordHash = await hashPassword(newPassword, salt);
    await env.DB.prepare("UPDATE parents SET password_hash = ?, password_salt = ? WHERE parent_id = ?")
      .bind(passwordHash, salt, p.parent_id).run();
    return json({ ok: true });
  }

  if (path === "/api/login" && method === "POST") {
    const b = await body(request);

    const email = String(b.email || "").trim().toLowerCase();
    const password = String(b.password || "");

    const p = await env.DB
      .prepare("SELECT * FROM parents WHERE email = ?")
      .bind(email)
      .first();

    if (!p) {
      return json({ error: "Invalid email or password" }, 401);
    }

    const passwordHash = await hashPassword(
      password,
      p.password_salt
    );

    if (passwordHash !== p.password_hash) {
      return json({ error: "Invalid email or password" }, 401);
    }

    // Login creates a session only. The free 24-hour trial starts after the
    // first Child is successfully paired, not when the Parent creates/logs into an account.
    let token = p.token;
    if (!token) {
      token = makeToken("parent");
      await env.DB
        .prepare("UPDATE parents SET token = ? WHERE parent_id = ?")
        .bind(token, p.parent_id)
        .run();
    }

    const fresh = await env.DB
      .prepare("SELECT * FROM parents WHERE parent_id = ?")
      .bind(p.parent_id)
      .first();

    return json({
      token,
      parent: await parentSummary(env, fresh, request)
    });
  }

  if (path === "/api/me" && method === "GET") {
    const p = await requireParent(env, request);

    return json({
      parent: await parentSummary(env, p, request)
    });
  }

  // Billing-only Child sync is deliberately separate from Cloud control pairing.
  // Local Wi-Fi LOCK/UNLOCK can remain local while trial/payment records sync here.
  if (path === "/api/billing/children/register" && method === "POST") {
    const p = await requireParent(env, request);
    const b = await body(request);
    try {
      const child = await registerBillingChild(env, p, {
        childKey: b.childKey,
        code: b.code,
        name: b.name,
        pairedAt: b.pairedAt
      });
      const summary = await billingSummaryForParent(env, p);
      return json({ ok: true, child, billing: summary });
    } catch (e) {
      if (e instanceof Response) return e;
      throw e;
    }
  }

  if (path === "/api/billing/summary" && method === "GET") {
    const p = await requireParent(env, request);
    return json({ billing: await billingSummaryForParent(env, p) });
  }

  if (path === "/api/child/register" && method === "POST") {
    const b = await body(request);

    const name =
      String(b.name || "Child").trim() || "Child";
    const registrationKey = String(b.deviceKey || "").trim();
    let registrationSettingKey = "";
    if (registrationKey) {
      const registrationHash = await sha256(registrationKey);
      registrationSettingKey = `child_registration_${registrationHash}`;
      const previousChildId = await setting(env, registrationSettingKey, "");
      if (previousChildId) {
        const previous = await env.DB
          .prepare("SELECT * FROM children WHERE child_id = ?")
          .bind(previousChildId)
          .first();
        if (previous) {
          return json({
            child: { childId: previous.child_id, name: previous.name, code: previous.code },
            token: previous.token
          });
        }
      }
    }

    const childId = makeId("C");
    // Optional caller-selected code lets the Android Child use the same 6-digit
    // code on the local Wi-Fi link and in the cloud. Existing clients that omit
    // it keep the original generated-code behavior.
    const requestedCode = String(b.code || "").trim();
    let code;
    if (requestedCode) {
      if (!/^\d{6}$/.test(requestedCode)) {
        return json({ error: "Child code must be 6 digits" }, 400);
      }
      const collision = await env.DB
        .prepare("SELECT child_id FROM children WHERE code = ?")
        .bind(requestedCode)
        .first();
      if (collision) {
        return json({ error: "Requested Child code is already in use" }, 409);
      }
      code = requestedCode;
    } else {
      code = await generateUniqueChildCode(env);
    }
    const childToken = makeToken("child");
    const createdAt = now();

    await env.DB
      .prepare(`
        INSERT INTO children
        (
          child_id,
          name,
          code,
          token,
          parent_id,
          last_seen_at,
          locked,
          command,
          created_at
        )
        VALUES (?, ?, ?, ?, NULL, ?, 0, 'UNLOCK', ?)
      `)
      .bind(
        childId,
        name,
        code,
        childToken,
        createdAt,
        createdAt
      )
      .run();

    if (registrationSettingKey) {
      await env.DB.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
        .bind(registrationSettingKey, childId)
        .run();
    }

    return json({
      child: {
        childId,
        name,
        code
      },
      token: childToken
    });
  }

  if (path === "/api/pair" && method === "POST") {
    const p = await requireParent(env, request);
    const b = await body(request);

    const code = String(b.code || "").trim();

    if (!/^\d{6}$/.test(code)) {
      return json(
        { error: "Child code must be 6 digits" },
        400
      );
    }

    const c = await env.DB
      .prepare("SELECT * FROM children WHERE code = ?")
      .bind(code)
      .first();

    if (!c) {
      return json(
        { error: "Child code not found" },
        404
      );
    }

    if (
      c.parent_id &&
      c.parent_id !== p.parent_id
    ) {
      return json(
        { error: "Child is already paired" },
        409
      );
    }

    await env.DB
      .prepare(`
        UPDATE children
        SET parent_id = ?
        WHERE child_id = ?
      `)
      .bind(p.parent_id, c.child_id)
      .run();

    const fresh = await env.DB
      .prepare("SELECT * FROM children WHERE child_id = ?")
      .bind(c.child_id)
      .first();

    const billingChild = await registerBillingChild(env, p, {
      childKey: `cloud:${fresh.child_id}`,
      code: fresh.code,
      name: fresh.name,
      pairedAt: now()
    });

    return json({
      ok: true,
      child: { ...childSummary(fresh), ...billingChild }
    });
  }

  if (path === "/api/children" && method === "GET") {
    const p = await requireParent(env, request);

    const rows = await env.DB
      .prepare(`
        SELECT *
        FROM children
        WHERE parent_id = ?
        ORDER BY created_at DESC
      `)
      .bind(p.parent_id)
      .all();

    return json({
      children: await Promise.all((rows.results || []).map(async (child) => {
        const rawCommand = await setting(env, `last_command_${child.child_id}`, "");
        let record = {};
        try { record = rawCommand ? JSON.parse(rawCommand) : {}; } catch {}
        const active = String(child.command || "").toUpperCase() === "LOCK" &&
          String(record.command || "").toUpperCase() === "LOCK";
        const billingRows = await billingRowsForParent(env, p.parent_id);
        const billing = billingRows.find(row => row.child_code === child.code);
        const billingView = billing ? billingChildView(billing, billingRows) : {};
        return { ...childSummary(child), ...billingView, unlockAt: active ? Number(record.unlockAt || 0) : 0 };
      }))
    });
  }

  const commandMatch =
    path.match(/^\/api\/children\/([^/]+)\/command$/);

  if (commandMatch && method === "POST") {
    const p = await requireParent(env, request);
    const childId = commandMatch[1];
    const b = await body(request);

    const command =
      String(b.command || "").trim().toUpperCase();

    if (!["LOCK", "UNLOCK"].includes(command)) {
      return json(
        { error: "Command must be LOCK or UNLOCK" },
        400
      );
    }

    const c = await env.DB
      .prepare(`
        SELECT *
        FROM children
        WHERE child_id = ?
          AND parent_id = ?
      `)
      .bind(childId, p.parent_id)
      .first();

    if (!c) {
      return json(
        { error: "Child not found" },
        404
      );
    }

    if (command === "LOCK") {
      const ledger = await env.DB.prepare(
        "SELECT * FROM billing_children WHERE parent_id = ? AND child_code = ?"
      ).bind(p.parent_id, c.code).first();

      const allLedger = await billingRowsForParent(env, p.parent_id);
      const decision = lockBillingDecision(ledger, allLedger);

      if (!decision.allowed && decision.reason === "BILLING_SYNC_REQUIRED") {
        return json({
          error: "BILLING_SYNC_REQUIRED: Refresh the Parent app while connected to the Internet before using LOCK.",
          paymentRequired: false,
          billingSyncRequired: true
        }, 409);
      }

      if (!decision.allowed) {
        const entitlement = billingChildView(ledger, allLedger);
        return json({
          error: `PAYMENT_REQUIRED: ${entitlement.name} must be paid before LOCK control is available.`,
          paymentRequired: true,
          child: entitlement
        }, 402);
      }
    }
    const commandAt = now();
    const requestedUnlockAt = b.unlockAt == null ? 0 : Number(b.unlockAt);
    if (!Number.isFinite(requestedUnlockAt) || requestedUnlockAt < 0) {
      return json({ error: "Invalid Auto Unlock time" }, 400);
    }
    if (command === "LOCK" && requestedUnlockAt > 0 && requestedUnlockAt <= commandAt) {
      return json({ error: "Auto Unlock time must be in the future" }, 400);
    }
    const unlockAt = command === "LOCK" ? requestedUnlockAt : 0;
    await env.DB.batch([
      env.DB.prepare(`
        UPDATE children
        SET command = ?, locked = ?
        WHERE child_id = ?
      `).bind(command, command === "LOCK" ? 1 : 0, childId),
      env.DB.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
        .bind(`last_command_${childId}`, JSON.stringify({ command, at: commandAt, unlockAt }))
    ]);

    const fresh = await env.DB
      .prepare("SELECT * FROM children WHERE child_id = ?")
      .bind(childId)
      .first();

    return json({
      ok: true,
      command,
      child: { ...childSummary(fresh), unlockAt }
    });
  }

  // Child-authenticated, date-guarded daily auto-unlock. Uses the existing
  // settings table instead of requiring a new D1 column/migration.
  if (path === "/api/child/auto-unlock" && method === "POST") {
    const c = await requireChild(env, request);
    const requestBody = (await body(request)) || {};

    // Parent-selected timer completion is separate from the daily 05:00 flow.
    if (Object.prototype.hasOwnProperty.call(requestBody, "scheduledUnlockAt")) {
      const scheduledUnlockAt = Number(requestBody.scheduledUnlockAt);
      if (!Number.isFinite(scheduledUnlockAt) || scheduledUnlockAt <= 0) {
        return json({ error: "Invalid scheduled unlock timestamp" }, 400);
      }

      const rawCommand = await setting(env, `last_command_${c.child_id}`, "");
      let record = {};
      try { record = rawCommand ? JSON.parse(rawCommand) : {}; } catch {}
      const activeUnlockAt = Number(record.unlockAt || 0);

      if (String(record.command || "").toUpperCase() !== "LOCK" || activeUnlockAt !== scheduledUnlockAt) {
        return json({ ok: true, skipped: true, reason: "Scheduled unlock is no longer active", command: c.command || "UNLOCK", locked: Boolean(c.locked), unlockAt: activeUnlockAt });
      }
      if (scheduledUnlockAt > now()) {
        return json({ error: "Scheduled unlock time has not arrived" }, 409);
      }

      const completedAt = now();
      const result = await env.DB.prepare(
        "UPDATE children SET command = 'UNLOCK', locked = 0, last_seen_at = ? WHERE child_id = ? AND command = 'LOCK'"
      ).bind(completedAt, c.child_id).run();
      if (Number(result?.meta?.changes || 0) === 0) {
        return json({ ok: true, skipped: true, reason: "Child is no longer locked", command: c.command || "UNLOCK", locked: Boolean(c.locked) });
      }

      await env.DB.prepare(
        "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"
      ).bind(`last_command_${c.child_id}`, JSON.stringify({ command: "UNLOCK", at: completedAt, unlockAt: 0 })).run();

      return json({ ok: true, scheduledUnlockCompleted: true, command: "UNLOCK", locked: false, unlockAt: 0, lastCommandAt: completedAt });
    }

    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone: "Asia/Manila",
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", hourCycle: "h23"
    }).formatToParts(new Date(now()));
    const part = (name) => parts.find(x => x.type === name)?.value || "00";
    const dateKey = `${part("year")}-${part("month")}-${part("day")}`;
    const minutes = Number(part("hour")) * 60 + Number(part("minute"));
    if (minutes < 300) {
      return json({ ok: false, error: "Daily auto-unlock is available from 05:00 Asia/Manila", command: c.command || "UNLOCK", locked: Boolean(c.locked) }, 409);
    }

    const settingKey = `daily_auto_unlock_${c.child_id}`;
    const previousDate = await setting(env, settingKey, "");
    if (previousDate === dateKey) {
      return json({ ok: true, alreadyApplied: true, date: dateKey, command: c.command || "UNLOCK", locked: Boolean(c.locked) });
    }

    const lastCommandRaw = await setting(env, `last_command_${c.child_id}`, "");
    let lastCommand = {};
    try { lastCommand = lastCommandRaw ? JSON.parse(lastCommandRaw) : {}; } catch {}
    const unlockInstant = Date.parse(`${dateKey}T05:00:00+08:00`);
    const lockedAfterFive = String(lastCommand.command || "").toUpperCase() === "LOCK"
      && Number(lastCommand.at || 0) >= unlockInstant;

    if (lockedAfterFive) {
      await env.DB.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
        .bind(settingKey, dateKey)
        .run();
      return json({ ok: true, skipped: true, date: dateKey, reason: "Manual lock was sent after 05:00", command: "LOCK", locked: true });
    }

    await env.DB.batch([
      env.DB.prepare("UPDATE children SET command = 'UNLOCK', locked = 0, last_seen_at = ? WHERE child_id = ?")
        .bind(now(), c.child_id),
      env.DB.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
        .bind(settingKey, dateKey)
    ]);
    return json({ ok: true, alreadyApplied: false, date: dateKey, command: "UNLOCK", locked: false });
  }

  if (path === "/api/child/heartbeat" && method === "POST") {
    const c = await requireChild(env, request);

    const locked =
      String(c.command || "UNLOCK").toUpperCase() === "LOCK";

    await env.DB
      .prepare(`
        UPDATE children
        SET
          last_seen_at = ?,
          locked = ?
        WHERE child_id = ?
      `)
      .bind(
        now(),
        locked ? 1 : 0,
        c.child_id
      )
      .run();

    const lastCommandRaw = await setting(env, `last_command_${c.child_id}`, "");
    let lastCommand = {};
    try { lastCommand = JSON.parse(lastCommandRaw || "{}"); } catch {}
    const lastCommandAt = Number(lastCommand.at || 0);
    const unlockAt = String(c.command || "").toUpperCase() === "LOCK" &&
      String(lastCommand.command || "").toUpperCase() === "LOCK"
      ? Number(lastCommand.unlockAt || 0) : 0;
    return json({
      ok: true,
      command: c.command || "UNLOCK",
      locked,
      parentId: c.parent_id,
      lastCommandAt,
      unlockAt
    });
  }

  if (path === "/api/child/command" && method === "GET") {
    const c = await requireChild(env, request);
    const lastCommandRaw = await setting(env, `last_command_${c.child_id}`, "");
    let lastCommand = {};
    try { lastCommand = JSON.parse(lastCommandRaw || "{}"); } catch {}
    const lastCommandAt = Number(lastCommand.at || 0);
    const unlockAt = String(c.command || "").toUpperCase() === "LOCK" &&
      String(lastCommand.command || "").toUpperCase() === "LOCK"
      ? Number(lastCommand.unlockAt || 0) : 0;

    return json({
      command: c.command || "UNLOCK",
      locked: Boolean(c.locked),
      parentPaired: Boolean(c.parent_id),
      lastCommandAt,
      unlockAt
    });
  }

  if (path === "/api/child/unpair" && method === "POST") {
    const c = await requireChild(env, request);

    await env.DB.batch([
      env.DB
        .prepare(`
          UPDATE children
          SET
            parent_id = NULL,
            command = 'UNLOCK',
            locked = 0,
            last_seen_at = ?
          WHERE child_id = ?
        `)
        .bind(now(), c.child_id)
    ]);

    return json({
      ok: true
    });
  }

  if (path === "/api/payment/qr" && method === "GET") {
    const p = await requireParent(env, request);
    const s = await allSettings(env);

    return json({
      paymentQrUrl:
        new URL(
          String(s.payment_qr || "/payment-qr.png"),
          request.url
        ).toString(),

      initialPayment: Number(s.initial_payment || 300),
      registrationFee: Number(s.registration_fee || 100),
      annualFee: Number(s.annual_fee || 300),
      renewalFee: Number(s.renewal_fee || 300),

      parentStatus: await parentStatus(env, p)
    });
  }

  if (path === "/api/payment/checkout" && method === "POST") {
    const p = await requireParent(env, request);
    const rows = await billingRowsForParent(env, p.parent_id);
    const at = now();
    const due = rows
      .map(row => ({ row, view: billingChildView(row, rows, at) }))
      .filter(item => Number(item.view.amountDue || 0) > 0)
      .sort((a, b) => Number(a.row.child_order) - Number(b.row.child_order));
    const target = due.length ? due[0] : null;
    if (!target) {
      const trial = rows.find(row => effectiveBillingStatus(row, at) === "TRIAL");
      return json({
        error: trial
          ? "No payment is due yet. The first Child's free 24-hour trial is still active."
          : "No Child payment is due right now.",
        paymentDue: false
      }, 400);
    }

    let payment = await env.DB.prepare(`
      SELECT * FROM payments
      WHERE parent_id = ? AND billing_child_id = ? AND status = 'PENDING'
      ORDER BY created_at DESC LIMIT 1
    `).bind(p.parent_id, target.row.billing_child_id).first();
    if (payment && payment.payment_method !== "PAYMONGO") {
      return json({ error: "A manual proof payment is already awaiting review for this Child. Do not start a second payment." }, 409);
    }
    if (!payment) {
      const paymentId = makeId("PAY");
      const paymentType = Number(target.row.paid_until || 0) > 0 ? "CHILD_RENEWAL" : "CHILD_ACTIVATION";
      await env.DB.prepare(`
        INSERT INTO payments
          (payment_id, parent_id, payment_type, amount, registration_fee, annual_fee,
           status, created_at, approved_at, billing_child_id, billing_reason,
           reference_number, proof_name, payment_method)
        VALUES (?, ?, ?, ?, 0, ?, 'PENDING', ?, NULL, ?, ?, ?, NULL, 'PAYMONGO')
      `).bind(
        paymentId, p.parent_id, paymentType, Number(target.view.amountDue),
        Number(target.view.amountDue), at, target.row.billing_child_id,
        target.view.billingReason, paymentId
      ).run();
      payment = await env.DB.prepare("SELECT * FROM payments WHERE payment_id = ?").bind(paymentId).first();
    }
    return createPayMongoCheckout(env, payment, p, target.row);
  }

  if (path === "/api/payment/submit" && method === "POST") {
    const p = await requireParent(env, request);
    const b = await body(request);
    const rows = await billingRowsForParent(env, p.parent_id);

    if (rows.length > 0) {
      const at = now();
      const due = rows
        .map(row => ({ row, view: billingChildView(row, rows, at) }))
        .filter(item => Number(item.view.amountDue || 0) > 0)
        .sort((a, b) => Number(a.row.child_order) - Number(b.row.child_order));
      const target = due.length ? due[0] : null;
      if (!target) {
        const trial = rows.find(row => effectiveBillingStatus(row, at) === "TRIAL");
        return json({
          error: trial
            ? "No payment is due yet. The first Child's free 24-hour trial is still active."
            : "No Child payment is due right now.",
          paymentDue: false
        }, 400);
      }

      let existing = await env.DB.prepare(`
        SELECT * FROM payments
        WHERE parent_id = ? AND billing_child_id = ? AND status = 'PENDING'
        ORDER BY created_at DESC LIMIT 1
      `).bind(p.parent_id, target.row.billing_child_id).first();
      if (existing && (existing.payment_method === "PAYMONGO" || existing.paymongo_checkout_session_id)) {
        return json({ error: "A PayMongo checkout is already pending for this Child. Complete it or contact support before submitting manual proof." }, 409);
      }
      if (existing?.proof_object_key) {
        return json({ ok: true, payment: {
          paymentId: existing.payment_id,
          paymentType: existing.payment_type,
          billingChildId: target.row.billing_child_id,
          childName: target.row.name,
          amount: Number(existing.amount),
          referenceNumber: existing.reference_number,
          proofName: existing.proof_name,
          status: "PENDING",
          proofAlreadySubmitted: true
        } });
      }

      const proof = parsePaymentProof(env, b);
      const duplicate = await checkDuplicatePaymentReference(env, proof.reference, existing?.payment_id || "");
      if (duplicate) return json({ error: "This transaction/reference number is already attached to another payment." }, 409);

      const paymentId = existing?.payment_id || makeId("PAY");
      const paymentType = existing?.payment_type || (Number(target.row.paid_until || 0) > 0 ? "CHILD_RENEWAL" : "CHILD_ACTIVATION");
      const proofKey = await storePaymentProof(env, paymentId, p.parent_id, proof);
      try {
        if (existing) {
          const update = await env.DB.prepare(`
            UPDATE payments
            SET reference_number = ?, proof_name = ?, proof_object_key = ?, proof_content_type = ?,
                proof_size = ?, proof_submitted_at = ?, review_note = NULL
            WHERE payment_id = ? AND parent_id = ? AND status = 'PENDING'
              AND payment_method = 'MANUAL' AND proof_object_key IS NULL
          `).bind(proof.reference, proof.proofName, proofKey, proof.contentType, proof.size, at,
            paymentId, p.parent_id).run();
          if (Number(update?.meta?.changes || 0) !== 1) {
            await env.PAYMENT_PROOFS.delete(proofKey);
            return json({ error: "Payment changed while attaching proof. Refresh and try again." }, 409);
          }
        } else {
          await env.DB.prepare(`
            INSERT INTO payments
              (payment_id, parent_id, payment_type, amount, registration_fee, annual_fee,
               status, created_at, approved_at, billing_child_id, billing_reason,
               reference_number, proof_name, payment_method, proof_object_key,
               proof_content_type, proof_size, proof_submitted_at)
            VALUES (?, ?, ?, ?, 0, ?, 'PENDING', ?, NULL, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?)
          `).bind(
            paymentId, p.parent_id, paymentType, Number(target.view.amountDue), Number(target.view.amountDue),
            at, target.row.billing_child_id, target.view.billingReason, proof.reference, proof.proofName,
            proofKey, proof.contentType, proof.size, at
          ).run();
        }
      } catch (error) {
        await env.PAYMENT_PROOFS.delete(proofKey).catch(() => {});
        throw error;
      }

      return json({ ok: true, payment: {
        paymentId,
        paymentType,
        billingChildId: target.row.billing_child_id,
        childName: target.row.name,
        billingReason: target.view.billingReason,
        amount: Number(target.view.amountDue),
        referenceNumber: proof.reference,
        proofName: proof.proofName,
        status: "PENDING"
      } });
    }

    return json({
      error: "Pair the first Child before the free 24-hour trial or payment starts.",
      paymentDue: false
    }, 400);
  }

  if (path === "/api/admin/payments" && method === "GET") {
    if (!adminKeyAuthorized(request, env)) {
      return json({ error: "Unauthorized" }, 401);
    }

    const rows = await env.DB
      .prepare(`
        SELECT
          pay.*,
          p.name AS parent_name,
          p.email AS parent_email,
          bc.name AS billing_child_name,
          bc.child_code AS billing_child_code
        FROM payments pay
        LEFT JOIN parents p ON p.parent_id = pay.parent_id
        LEFT JOIN billing_children bc ON bc.billing_child_id = pay.billing_child_id
        ORDER BY pay.created_at DESC
      `)
      .all();

    return json({
      payments: (rows.results || []).map(x => ({
        paymentId: x.payment_id,
        parentId: x.parent_id,
        parentName: x.parent_name,
        parentEmail: x.parent_email,
        paymentType: x.payment_type,
        amount: x.amount,
        registrationFee: x.registration_fee,
        annualFee: x.annual_fee,
        billingChildId: x.billing_child_id || null,
        billingChildName: x.billing_child_name || null,
        billingChildCode: x.billing_child_code || null,
        billingReason: x.billing_reason || null,
        referenceNumber: x.reference_number || null,
        proofName: x.proof_name || null,
        paymentMethod: x.payment_method || "MANUAL",
        hasProof: Boolean(x.proof_object_key),
        proofSize: Number(x.proof_size || 0),
        checkoutUrl: x.checkout_url || null,
        paymongoCheckoutSessionId: x.paymongo_checkout_session_id || null,
        paymongoPaymentId: x.paymongo_payment_id || null,
        paidAt: iso(x.paid_at),
        reviewNote: x.review_note || null,
        rejectedAt: iso(x.rejected_at),
        status: x.status,
        createdAt: iso(x.created_at),
        approvedAt: iso(x.approved_at)
      }))
    });
  }

  const proofMatch = path.match(/^\/api\/admin\/payments\/([^/]+)\/proof$/);
  if (proofMatch && method === "GET") {
    if (!adminKeyAuthorized(request, env)) return json({ error: "Unauthorized" }, 401);
    if (!env.PAYMENT_PROOFS) return json({ error: "Private proof storage is not configured." }, 503);
    const paymentId = proofMatch[1];
    const row = await env.DB.prepare(`
      SELECT proof_object_key, proof_content_type, proof_size
      FROM payments WHERE payment_id = ?
    `).bind(paymentId).first();
    if (!row?.proof_object_key) return json({ error: "Proof image not found." }, 404);
    const object = await env.PAYMENT_PROOFS.get(row.proof_object_key);
    if (!object) return json({ error: "Private proof object is missing." }, 404);
    return new Response(object.body, { headers: {
      "content-type": row.proof_content_type || "application/octet-stream",
      "content-length": String(row.proof_size || object.size),
      "content-disposition": "inline",
      "cache-control": "private, no-store",
      "x-content-type-options": "nosniff",
      "content-security-policy": "default-src 'none'; sandbox",
      "access-control-allow-origin": "*"
    }});
  }

  const approveMatch =
    path.match(/^\/api\/admin\/payments\/([^/]+)\/approve$/);

  if (approveMatch && method === "POST") {
    if (!adminKeyAuthorized(request, env)) {
      return json({ error: "Unauthorized" }, 401);
    }

    const paymentId = approveMatch[1];

    const payment = await env.DB
      .prepare("SELECT * FROM payments WHERE payment_id = ?")
      .bind(paymentId)
      .first();

    if (!payment) {
      return json(
        { error: "Payment not found" },
        404
      );
    }

    if (payment.status !== "PENDING") {
      return json(
        {
          error:
            `Payment is already ${payment.status}`
        },
        400
      );
    }

    if (payment.payment_method === "PAYMONGO" || payment.paymongo_checkout_session_id) {
      return json({ error: "PayMongo payments can only activate after a valid, signed PayMongo paid webhook." }, 409);
    }
    if (!payment.proof_object_key || !payment.reference_number) {
      return json({ error: "A stored payment proof and transaction/reference number are required before approval." }, 409);
    }

    const p = await env.DB
      .prepare("SELECT * FROM parents WHERE parent_id = ?")
      .bind(payment.parent_id)
      .first();

    if (!p) {
      return json(
        { error: "Parent not found" },
        404
      );
    }

    if (payment.billing_child_id) {
      const target = await env.DB.prepare(
        "SELECT * FROM billing_children WHERE billing_child_id = ? AND parent_id = ?"
      ).bind(payment.billing_child_id, payment.parent_id).first();
      if (!target) return json({ error: "Billing Child not found for payment" }, 404);

      const approvedAt = now();
      const paidUntil = approvedAt + 365 * DAY_MS;
      const approvalResults = await env.DB.batch([
        env.DB.prepare("UPDATE payments SET status = 'APPROVED', approved_at = ?, paid_at = ? WHERE payment_id = ? AND status = 'PENDING' AND payment_method = 'MANUAL'")

         .bind(approvedAt, approvedAt, paymentId),
        env.DB.prepare(`
          UPDATE billing_children
          SET billing_status = 'ACTIVE', paid_until = ?, paid_at = ?, paid_amount = ?, updated_at = ?
          WHERE billing_child_id = ? AND parent_id = ? AND EXISTS (
            SELECT 1 FROM payments WHERE payment_id = ? AND status = 'APPROVED' AND approved_at = ?
          )
        `).bind(paidUntil, approvedAt, Number(payment.amount), approvedAt, target.billing_child_id, p.parent_id, paymentId, approvedAt)
      ]);
      if (Number(approvalResults?.[0]?.meta?.changes || 0) !== 1 || Number(approvalResults?.[1]?.meta?.changes || 0) !== 1) {
        return json({ error: "Payment changed during review. Refresh the queue before trying again." }, 409);
      }

      const freshBilling = await billingSummaryForParent(env, p);
      return json({
        ok: true,
        payment: { paymentId, status: "APPROVED", amount: Number(payment.amount), billingChildId: target.billing_child_id },
        billing: freshBilling
      });
    }

    const currentEnd =
      Number(p.subscription_ends_at || 0);

    const base =
      payment.payment_type === "RENEWAL"
        ? Math.max(now(), currentEnd)
        : now();

    const newEnd =
      base +
      365 * 24 * 60 * 60 * 1000;

    const approvedAt = now();

    await env.DB.batch([
      env.DB
        .prepare(`
          UPDATE payments
          SET
            status = 'APPROVED',
            approved_at = ?,
            paid_at = ?
          WHERE payment_id = ?
        `)
        .bind(approvedAt, approvedAt, paymentId),

      env.DB
        .prepare(`
          UPDATE parents
          SET
            subscription_status = 'ACTIVE',
            subscription_ends_at = ?
          WHERE parent_id = ?
        `)
        .bind(newEnd, p.parent_id)
    ]);

    const freshParent = await env.DB
      .prepare("SELECT * FROM parents WHERE parent_id = ?")
      .bind(p.parent_id)
      .first();

    return json({
      ok: true,
      payment: {
        paymentId,
        status: "APPROVED"
      },
      parent: await parentSummary(
        env,
        freshParent,
        request
      )
    });
  }

  const rejectMatch = path.match(/^\/api\/admin\/payments\/([^/]+)\/reject$/);
  if (rejectMatch && method === "POST") {
    if (!adminKeyAuthorized(request, env)) return json({ error: "Unauthorized" }, 401);
    const paymentId = rejectMatch[1];
    const noteBody = await body(request);
    const note = String(noteBody.note || "").trim().slice(0, 500);
    const payment = await env.DB.prepare("SELECT * FROM payments WHERE payment_id = ?").bind(paymentId).first();
    if (!payment) return json({ error: "Payment not found" }, 404);
    if (payment.status !== "PENDING") return json({ error: `Payment is already ${payment.status}` }, 409);
    if (payment.payment_method === "PAYMONGO" || payment.paymongo_checkout_session_id) {
      return json({ error: "PayMongo checkout status is controlled by the verified provider webhook; manual rejection is disabled." }, 409);
    }
    const at = now();
    const update = await env.DB.prepare(`
      UPDATE payments SET status = 'REJECTED', rejected_at = ?, review_note = ?
      WHERE payment_id = ? AND status = 'PENDING' AND payment_method = 'MANUAL'
    `).bind(at, note || "Payment proof rejected during admin review.", paymentId).run();
    if (Number(update?.meta?.changes || 0) !== 1) return json({ error: "Payment changed during review. Refresh the queue." }, 409);
    return json({ ok: true, payment: { paymentId, status: "REJECTED", rejectedAt: iso(at), reviewNote: note } });
  }

  return null;
}

export default {
  async fetch(request, env) {
    try {
      const result = await route(request, env);

      if (result) {
        return result;
      }

      return env.ASSETS.fetch(request);

    } catch (e) {
      if (e instanceof Response) {
        return e;
      }

      return json(
        {
          error: "Server error",
          detail: String(e?.message || e)
        },
        500
      );
    }
  }
};