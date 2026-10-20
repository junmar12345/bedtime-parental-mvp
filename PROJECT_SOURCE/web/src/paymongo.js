/*
 * BEDTIME MASTER PayMongo Checkout V2 helper, adapted for the current per-Child ledger.
 * This code is staged only. Configure secrets and a separate staging D1/R2 before testing.
 * Required bindings: DB, PAYMENT_PROOFS (R2), PAYMONGO_MODE, PAYMONGO_SECRET_KEY,
 * PAYMONGO_WEBHOOK_SECRET, PUBLIC_BASE_URL. Never put secrets in source control.
 */
const YEAR_MS = 365 * 24 * 60 * 60 * 1000;

function response(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*"
    }
  });
}

function constantTimeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let mismatch = 0;
  for (let i = 0; i < a.length; i++) mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return mismatch === 0;
}

function hex(bytes) {
  return Array.from(new Uint8Array(bytes), value => value.toString(16).padStart(2, "0")).join("");
}

async function signRawBody(rawBody, timestamp, secret) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return hex(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${timestamp}.${rawBody}`)));
}

export async function verifyPayMongoSignature(rawBody, header, secret, mode) {
  if (typeof rawBody !== "string" || !header || !secret || !["test", "live"].includes(mode)) return false;
  const fields = {};
  for (const part of header.split(",")) {
    const at = part.indexOf("=");
    if (at > 0) fields[part.slice(0, at).trim()] = part.slice(at + 1).trim();
  }
  if (!/^\d+$/.test(fields.t || "")) return false;
  const timestamp = Number(fields.t);
  const current = Math.floor(Date.now() / 1000);
  if (!Number.isSafeInteger(timestamp) || Math.abs(current - timestamp) > 300) return false;
  const supplied = mode === "live" ? fields.li : fields.te;
  if (!/^[0-9a-f]{64}$/i.test(supplied || "")) return false;
  const expected = await signRawBody(rawBody, fields.t, secret);
  return constantTimeEqual(expected.toLowerCase(), supplied.toLowerCase());
}

function safeHttpsBase(value) {
  try {
    const url = new URL(String(value || ""));
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) return null;
    return url;
  } catch { return null; }
}

function validSecretKey(mode, key) {
  return mode === "test" ? /^sk_test_/.test(key) : mode === "live" && /^sk_live_/.test(key);
}

export async function createPayMongoCheckout(env, payment, parent, child) {
  const mode = String(env.PAYMONGO_MODE || "");
  const secretKey = String(env.PAYMONGO_SECRET_KEY || "");
  const webhookSecret = String(env.PAYMONGO_WEBHOOK_SECRET || "");
  const publicBase = safeHttpsBase(env.PUBLIC_BASE_URL);
  if (!validSecretKey(mode, secretKey) || !webhookSecret || !publicBase) {
    return response({ error: "PayMongo is not configured for this environment." }, 503);
  }
  if (!env.DB || !payment || payment.status !== "PENDING" || payment.payment_method !== "PAYMONGO" || !payment.billing_child_id) {
    return response({ error: "Payment is not eligible for PayMongo checkout." }, 409);
  }
  if (payment.checkout_url && payment.paymongo_checkout_session_id) {
    return response({ ok: true, payment: {
      paymentId: payment.payment_id,
      amount: Number(payment.amount),
      status: payment.status
    }, checkoutUrl: payment.checkout_url });
  }
  if (payment.paymongo_checkout_session_id && !payment.checkout_url) {
    return response({ error: "A checkout session is stored but its URL is missing. Admin review is required." }, 409);
  }

  const amountCents = Math.round(Number(payment.amount) * 100);
  if (!Number.isSafeInteger(amountCents) || amountCents <= 0) return response({ error: "Invalid payment amount." }, 400);

  const successUrl = new URL("/payment-return.html", publicBase);
  successUrl.searchParams.set("payment", "success");
  successUrl.searchParams.set("reference", payment.payment_id);
  const cancelUrl = new URL("/payment-return.html", publicBase);
  cancelUrl.searchParams.set("payment", "cancel");
  cancelUrl.searchParams.set("reference", payment.payment_id);

  const allowlist = new Set([
    "qrph", "gcash", "card", "paymaya", "grab_pay", "dob", "dob_ubp",
    "billease", "brankas_bdo", "brankas_landbank", "brankas_metrobank", "brankas_pnb"
  ]);
  const configured = String(env.PAYMONGO_PAYMENT_METHOD_TYPES || "qrph")
    .split(",").map(value => value.trim().toLowerCase()).filter(Boolean);
  const methods = [...new Set(configured)].filter(value => allowlist.has(value));
  if (!methods.length) return response({ error: "No valid PayMongo payment methods are configured." }, 503);

  const attrs = {
    line_items: [{
      name: `BEDTIME MASTER — ${child?.name || "Child"} subscription`,
      amount: amountCents,
      currency: "PHP",
      quantity: 1
    }],
    payment_method_types: methods,
    success_url: successUrl.toString(),
    cancel_url: cancelUrl.toString(),
    reference_number: String(payment.payment_id),
    send_email_receipt: Boolean(parent?.email),
    metadata: {
      system: "BEDTIME_MASTER",
      parent_id: String(payment.parent_id),
      payment_id: String(payment.payment_id),
      billing_child_id: String(payment.billing_child_id)
    }
  };
  if (parent && (parent.name || parent.email)) {
    attrs.billing = {};
    if (parent.name) attrs.billing.name = String(parent.name).slice(0, 255);
    if (parent.email) attrs.billing.email = String(parent.email).slice(0, 255);
  }

  let providerResponse;
  let providerData;
  try {
    providerResponse = await fetch("https://api.paymongo.com/v2/checkout_sessions", {
      method: "POST",
      headers: {
        "Authorization": `Basic ${btoa(`${secretKey}:`)}`,
        "Content-Type": "application/json",
        "Idempotency-Key": `BEDTIME-${payment.payment_id}`
      },
      body: JSON.stringify({ data: { attributes: attrs } })
    });
    providerData = await providerResponse.json();
  } catch {
    return response({ error: "Could not reach PayMongo. Your pending payment is still available for retry." }, 502);
  }
  if (!providerResponse.ok) {
    return response({
      error: "PayMongo rejected the checkout request.",
      providerStatus: providerResponse.status,
      providerCode: typeof providerData?.errors?.[0]?.code === "string" ? providerData.errors[0].code : undefined
    }, 502);
  }

  const session = providerData?.data;
  const sessionId = session?.id;
  const checkoutUrl = session?.attributes?.checkout_url;
  const liveMode = session?.attributes?.livemode;
  let parsedUrl;
  try { parsedUrl = new URL(checkoutUrl); } catch { parsedUrl = null; }
  if (
    typeof sessionId !== "string" || !sessionId.startsWith("cs_") ||
    !parsedUrl || parsedUrl.protocol !== "https:" || parsedUrl.hostname !== "checkout.paymongo.com" ||
    typeof liveMode !== "boolean" || liveMode !== (mode === "live")
  ) return response({ error: "PayMongo checkout response did not pass validation." }, 502);

  await env.DB.prepare(`
    UPDATE payments
    SET paymongo_checkout_session_id = ?, checkout_url = ?
    WHERE payment_id = ? AND status = 'PENDING' AND payment_method = 'PAYMONGO'
      AND (paymongo_checkout_session_id IS NULL OR paymongo_checkout_session_id = ?)
  `).bind(sessionId, checkoutUrl, payment.payment_id, sessionId).run();
  const saved = await env.DB.prepare("SELECT * FROM payments WHERE payment_id = ?").bind(payment.payment_id).first();
  if (!saved || saved.paymongo_checkout_session_id !== sessionId || saved.checkout_url !== checkoutUrl) {
    return response({ error: "Checkout was created but could not be safely linked to the pending Child payment." }, 409);
  }
  return response({ ok: true, payment: {
    paymentId: saved.payment_id,
    amount: Number(saved.amount),
    status: saved.status
  }, checkoutUrl: saved.checkout_url });
}

async function setEventStatus(env, key, status, errorCode = null) {
  await env.DB.prepare(`UPDATE paymongo_webhook_events SET status = ?, processed_at = ?, error_code = ? WHERE event_key = ?`)
    .bind(status, Date.now(), errorCode, key).run();
}

export async function handlePayMongoWebhook(request, env) {
  const mode = String(env.PAYMONGO_MODE || "");
  const webhookSecret = String(env.PAYMONGO_WEBHOOK_SECRET || "");
  if (!env.DB || !["test", "live"].includes(mode) || !webhookSecret) {
    return response({ error: "PayMongo webhook is not configured." }, 503);
  }
  const rawBody = await request.text();
  const signature = request.headers.get("Paymongo-Signature") || request.headers.get("X-Paymongo-Signature");
  let valid = false;
  try { valid = await verifyPayMongoSignature(rawBody, signature, webhookSecret, mode); } catch { valid = false; }
  if (!valid) return response({ error: "Invalid PayMongo webhook signature." }, 401);

  let payload;
  try { payload = JSON.parse(rawBody); } catch { return response({ error: "Invalid webhook JSON." }, 400); }
  // PayMongo currently documents this envelope:
  // payload.data.type = "checkout_session.payment.paid",
  // payload.data.livemode, and payload.data.data = the Checkout Session.
  // Also accept the older { data: { type: "event", attributes: ... } } envelope.
  const envelope = payload?.data;
  let eventType;
  let liveMode;
  let session;
  let providerEventId = null;

  if (
    envelope?.type === "event" &&
    envelope.attributes &&
    typeof envelope.attributes.type === "string"
  ) {
    const eventAttrs = envelope.attributes;
    eventType = eventAttrs.type;
    liveMode = eventAttrs.livemode;
    session = eventAttrs.data;
    providerEventId = typeof envelope.id === "string" ? envelope.id : null;
  } else if (
    envelope &&
    typeof envelope.type === "string" &&
    envelope.type !== "event" &&
    typeof envelope.livemode === "boolean" &&
    envelope.data &&
    typeof envelope.data === "object"
  ) {
    eventType = envelope.type;
    liveMode = envelope.livemode;
    session = envelope.data;
    providerEventId = typeof envelope.id === "string" ? envelope.id : null;
  } else {
    return response({ error: "Webhook event envelope mismatch." }, 400);
  }

  if (typeof liveMode !== "boolean" || liveMode !== (mode === "live")) {
    return response({ error: "Webhook mode or envelope mismatch." }, 400);
  }
  if (eventType !== "checkout_session.payment.paid") return response({ ok: true, ignored: true });

  const sessionId = session?.id;
  const attrs = session?.attributes;
  const reference = attrs?.reference_number;
  if (typeof sessionId !== "string" || !sessionId.startsWith("cs_") || typeof reference !== "string") {
    return response({ error: "Checkout session/reference is missing." }, 400);
  }
  const eventKey = `${eventType}:${sessionId}`;
  const receivedAt = Date.now();
  await env.DB.prepare(`
    INSERT OR IGNORE INTO paymongo_webhook_events
      (event_key, provider_event_id, event_type, checkout_session_id, payment_id, status, received_at)
    VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?)
  `).bind(eventKey, providerEventId, eventType, sessionId, reference, receivedAt).run();
  const claim = await env.DB.prepare(`
    UPDATE paymongo_webhook_events SET status = 'PROCESSING', error_code = NULL
    WHERE event_key = ? AND (status = 'RECEIVED' OR (status = 'PROCESSING' AND received_at < ?))
  `).bind(eventKey, receivedAt - 5 * 60 * 1000).run();
  if (Number(claim?.meta?.changes || 0) !== 1) {
    const previous = await env.DB.prepare("SELECT status FROM paymongo_webhook_events WHERE event_key = ?").bind(eventKey).first();
    if (["PROCESSED", "IGNORED", "REJECTED"].includes(previous?.status)) return response({ ok: true, duplicate: true });
    return response({ ok: true, processing: true });
  }
  const reject = async code => {
    await setEventStatus(env, eventKey, "REJECTED", code);
    return response({ ok: true, ignored: true });
  };

  const payment = await env.DB.prepare("SELECT * FROM payments WHERE payment_id = ?").bind(reference).first();
  if (!payment || payment.paymongo_checkout_session_id !== sessionId || payment.payment_method !== "PAYMONGO") {
    return reject("PAYMENT_REFERENCE_MISMATCH");
  }
  const metadata = attrs?.metadata || {};
  if (String(metadata.payment_id || "") !== payment.payment_id ||
      String(metadata.billing_child_id || "") !== String(payment.billing_child_id || "") ||
      String(metadata.parent_id || "") !== String(payment.parent_id || "")) {
    return reject("PAYMENT_METADATA_MISMATCH");
  }
  const paid = Array.isArray(attrs?.payments)
    ? attrs.payments.find(item => item?.attributes?.status === "paid")
    : null;
  const paidAttrs = paid?.attributes;
  const expectedCents = Math.round(Number(payment.amount) * 100);
  if (!paid || !Number.isSafeInteger(expectedCents) || Number(paidAttrs?.amount) !== expectedCents || String(paidAttrs?.currency || "").toUpperCase() !== "PHP") {
    return reject("AMOUNT_OR_CURRENCY_MISMATCH");
  }
  if (payment.status !== "PENDING") {
    await setEventStatus(env, eventKey, "IGNORED", "PAYMENT_NOT_PENDING");
    return response({ ok: true, ignored: true });
  }
  const child = await env.DB.prepare("SELECT * FROM billing_children WHERE billing_child_id = ? AND parent_id = ?")
    .bind(payment.billing_child_id, payment.parent_id).first();
  if (!child) return reject("BILLING_CHILD_NOT_FOUND");

  const paidAt = Date.now();
  const paidUntil = paidAt + YEAR_MS;
  const paymentIntentId = typeof attrs?.payment_intent?.id === "string" ? attrs.payment_intent.id : null;
  const paidId = typeof paid.id === "string" ? paid.id : null;
  const results = await env.DB.batch([
    env.DB.prepare(`
      UPDATE payments
      SET status = 'APPROVED', approved_at = ?, paid_at = ?, paymongo_payment_id = ?, paymongo_payment_intent_id = ?
      WHERE payment_id = ? AND status = 'PENDING' AND payment_method = 'PAYMONGO'
        AND paymongo_checkout_session_id = ? AND amount = ? AND billing_child_id = ? AND parent_id = ?
    `).bind(paidAt, paidAt, paidId, paymentIntentId, payment.payment_id, sessionId, payment.amount, child.billing_child_id, payment.parent_id),
    env.DB.prepare(`
      UPDATE billing_children
      SET billing_status = 'ACTIVE', paid_until = ?, paid_at = ?, paid_amount = ?, updated_at = ?
      WHERE billing_child_id = ? AND parent_id = ? AND EXISTS (
        SELECT 1 FROM payments WHERE payment_id = ? AND status = 'APPROVED' AND paid_at = ?
      )
    `).bind(paidUntil, paidAt, Number(payment.amount), paidAt, child.billing_child_id, payment.parent_id, payment.payment_id, paidAt)
  ]);
  if (Number(results?.[0]?.meta?.changes || 0) !== 1 || Number(results?.[1]?.meta?.changes || 0) !== 1) {
    await setEventStatus(env, eventKey, "REJECTED", "DATABASE_UPDATE_NOT_APPLIED");
    return response({ ok: true, ignored: true });
  }
  await setEventStatus(env, eventKey, "PROCESSED");
  return response({ ok: true, processed: true, paymentId: payment.payment_id });
}
