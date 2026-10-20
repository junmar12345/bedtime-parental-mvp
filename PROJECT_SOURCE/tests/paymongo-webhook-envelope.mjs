import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
if (!globalThis.crypto) globalThis.crypto = webcrypto;
import { handlePayMongoWebhook } from "../web/src/paymongo.js";

const encoder = new TextEncoder();
const secret = "local-smoke-secret-only-not-a-real-key";
const paymentId = "PAY-SMOKE-100";
const childId = "BILLING-CHILD-SMOKE-1";
const parentId = "PARENT-SMOKE-1";
const sessionId = "cs_smoke_2026";

function makeEnv() {
  const payment = {
    payment_id: paymentId,
    parent_id: parentId,
    billing_child_id: childId,
    payment_method: "PAYMONGO",
    paymongo_checkout_session_id: sessionId,
    status: "PENDING",
    amount: 100
  };
  const child = { billing_child_id: childId, parent_id: parentId, billing_status: "PENDING_PAYMENT" };
  const eventStatuses = new Map();

  function prepare(sql) {
    return {
      sql,
      params: [],
      bind(...params) { this.params = params; return this; },
      async first() {
        if (sql.includes("SELECT * FROM payments WHERE payment_id = ?")) return payment;
        if (sql.includes("SELECT * FROM billing_children WHERE billing_child_id = ? AND parent_id = ?")) return child;
        if (sql.includes("SELECT status FROM paymongo_webhook_events WHERE event_key = ?")) {
          const status = eventStatuses.get(this.params[0]);
          return status ? { status } : null;
        }
        return null;
      },
      async run() {
        if (sql.includes("INSERT OR IGNORE INTO paymongo_webhook_events")) {
          const key = this.params[0];
          if (eventStatuses.has(key)) return { meta: { changes: 0 } };
          eventStatuses.set(key, "RECEIVED");
          return { meta: { changes: 1 } };
        }
        if (sql.includes("UPDATE paymongo_webhook_events SET status = 'PROCESSING'")) {
          const key = this.params[0];
          if (eventStatuses.get(key) === "RECEIVED") {
            eventStatuses.set(key, "PROCESSING");
            return { meta: { changes: 1 } };
          }
          return { meta: { changes: 0 } };
        }
        if (sql.includes("UPDATE paymongo_webhook_events SET status = ?, processed_at = ?, error_code = ?")) {
          eventStatuses.set(this.params[3], this.params[0]);
          return { meta: { changes: 1 } };
        }
        return { meta: { changes: 1 } };
      }
    };
  }

  return {
    PAYMONGO_MODE: "test",
    PAYMONGO_WEBHOOK_SECRET: secret,
    DB: {
      prepare,
      async batch(statements) {
        assert.equal(statements.length, 2);
        payment.status = "APPROVED";
        child.billing_status = "ACTIVE";
        return [{ meta: { changes: 1 } }, { meta: { changes: 1 } }];
      }
    },
    _payment: payment,
    _child: child,
    _eventStatuses: eventStatuses
  };
}

async function sign(body) {
  const t = String(Math.floor(Date.now() / 1000));
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const digest = await crypto.subtle.sign("HMAC", key, encoder.encode(`${t}.${body}`));
  const signature = [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, "0")).join("");
  return `t=${t},te=${signature},li=`;
}

function payload({ legacy = false } = {}) {
  const session = {
    id: sessionId,
    type: "checkout_session",
    attributes: {
      reference_number: paymentId,
      metadata: { payment_id: paymentId, billing_child_id: childId, parent_id: parentId },
      payment_intent: { id: "pi_smoke_1" },
      payments: [{ id: "pay_smoke_1", attributes: { status: "paid", amount: 10000, currency: "PHP" } }]
    }
  };
  if (legacy) {
    return { data: { id: "evt_legacy_smoke", type: "event", attributes: {
      type: "checkout_session.payment.paid", livemode: false, data: session
    } } };
  }
  return { event_type: "send.webhook", data: {
    id: "evt_current_smoke", type: "checkout_session.payment.paid", resource: "checkout_session",
    livemode: false, data: session
  } };
}

async function send(env, body, signature) {
  return handlePayMongoWebhook(new Request("https://example.test/api/paymongo/webhook", {
    method: "POST",
    headers: { "content-type": "application/json", "Paymongo-Signature": signature },
    body
  }), env);
}

{
  const env = makeEnv();
  const body = JSON.stringify(payload());
  const signature = await sign(body);
  const first = await send(env, body, signature);
  assert.equal(first.status, 200);
  assert.equal((await first.json()).processed, true, "current PayMongo envelope should process valid paid event");
  assert.equal(env._payment.status, "APPROVED");
  assert.equal(env._child.billing_status, "ACTIVE");
  const duplicate = await send(env, body, signature);
  assert.equal(duplicate.status, 200);
  assert.equal((await duplicate.json()).duplicate, true, "duplicate event must be idempotent");
}

{
  const env = makeEnv();
  const body = JSON.stringify(payload());
  const signature = await sign(body);
  const rejected = await send(env, body + " ", signature);
  assert.equal(rejected.status, 401, "tampered webhook body must be rejected before processing");
  assert.equal(env._payment.status, "PENDING");
}

{
  const env = makeEnv();
  const body = JSON.stringify(payload({ legacy: true }));
  const signature = await sign(body);
  const res = await send(env, body, signature);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).processed, true, "legacy event envelope should still work");
}

console.log("PASS: current PayMongo envelope, legacy envelope, tampered signature/body rejection, and duplicate delivery.");