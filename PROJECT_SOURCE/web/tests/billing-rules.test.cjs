'use strict';

// Lightweight Node smoke tests for the exact billing helper functions in index.js.
// This deliberately avoids connecting to any live or remote database.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const workerPath = path.join(__dirname, '..', 'src', 'index.js');
const source = fs.readFileSync(workerPath, 'utf8');

function extractFunction(name) {
  const marker = `function ${name}(`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `Missing ${name} in Worker source`);
  const brace = source.indexOf('{', start);
  let depth = 0;
  let inString = null;
  let escaped = false;
  for (let i = brace; i < source.length; i++) {
    const ch = source[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === inString) inString = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') { inString = ch; continue; }
    if (ch === '{') depth++;
    if (ch === '}') {
      depth--;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  throw new Error(`Could not parse function ${name}`);
}

const constants = source.match(/const DAY_MS\s*=.*?\nconst CHILD_SECOND_FEE\s*=.*?\nconst CHILD_EXTRA_FEE\s*=.*?\nconst CHILD_FIRST_EXPIRED_FEE\s*=.*?\nconst CHILD_FIRST_DISCOUNTED_FEE\s*=.*?;/s);
assert.ok(constants, 'Billing constants not found');
const effectiveFn = extractFunction('effectiveBillingStatus');
const amountFn = extractFunction('billingAmountDue');
const trialWindowFn = extractFunction('initialChildTrialWindow');
const lockFn = extractFunction('lockBillingDecision');
const FIXED_NOW = 2_000_000_000_000;
const { effectiveBillingStatus, billingAmountDue, lockBillingDecision, initialChildTrialWindow } = new Function(
  'now', `${constants[0]}\n${trialWindowFn}\n${effectiveFn}\n${amountFn}\n${lockFn}\nreturn { effectiveBillingStatus, billingAmountDue, lockBillingDecision, initialChildTrialWindow };`
)(() => FIXED_NOW);

const firstTrial = {
  billing_child_id: 'bc1', child_order: 1, billing_status: 'TRIAL',
  trial_ends_at: FIXED_NOW + 60_000, paid_until: null, paid_at: null, paid_amount: 0
};
const secondPending = {
  billing_child_id: 'bc2', child_order: 2, billing_status: 'PENDING_PAYMENT',
  trial_ends_at: null, paid_until: null, paid_at: null, paid_amount: 0
};
const thirdPending = {
  billing_child_id: 'bc3', child_order: 3, billing_status: 'PENDING_PAYMENT',
  trial_ends_at: null, paid_until: null, paid_at: null, paid_amount: 0
};

assert.equal(effectiveBillingStatus(firstTrial, FIXED_NOW), 'TRIAL');
assert.equal(billingAmountDue(firstTrial, [firstTrial, secondPending], FIXED_NOW), 0,
  'First Child must remain free while 24-hour trial is active');
assert.equal(billingAmountDue(secondPending, [firstTrial, secondPending], FIXED_NOW), 300,
  'Second Child activation must cost ₱300');
assert.equal(billingAmountDue(thirdPending, [firstTrial, secondPending, thirdPending], FIXED_NOW), 50,
  'Third and later Child activation must cost ₱50');

const expiredFirst = { ...firstTrial, billing_status: 'TRIAL', trial_ends_at: FIXED_NOW - 1 };
assert.equal(effectiveBillingStatus(expiredFirst, FIXED_NOW), 'EXPIRED');
assert.equal(billingAmountDue(expiredFirst, [expiredFirst], FIXED_NOW), 300,
  'First Child expired without a second Child paid during trial must cost ₱300');
const secondPaidDuringTrial = { ...secondPending, billing_status: 'ACTIVE', paid_at: expiredFirst.trial_ends_at - 1, paid_until: FIXED_NOW + 365 * 86_400_000, paid_amount: 300 };
assert.equal(billingAmountDue(expiredFirst, [expiredFirst, secondPaidDuringTrial], FIXED_NOW), 100,
  'First Child is ₱100 only when second Child payment was approved during the first trial');
const secondPaidAfterTrial = { ...secondPaidDuringTrial, paid_at: expiredFirst.trial_ends_at + 1 };
assert.equal(billingAmountDue(expiredFirst, [expiredFirst, secondPaidAfterTrial], FIXED_NOW), 300,
  'Second Child payment after trial expiry does not grant the ₱100 first-Child discount');
const secondPaidAtExactExpiry = { ...secondPaidDuringTrial, paid_at: expiredFirst.trial_ends_at };
assert.equal(billingAmountDue(expiredFirst, [expiredFirst, secondPaidAtExactExpiry], FIXED_NOW), 300,
  'Payment at the exact trial-expiry boundary is not considered paid during the trial');

console.log('PASS: first Child trial is free for 24 hours');
console.log('PASS: second Child = ₱300');
console.log('PASS: third and later Child = ₱50');
console.log('PASS: first Child after expiry = ₱300 without a timely second-Child payment');
console.log('PASS: first Child after expiry = ₱100 only if second-Child payment was approved strictly before trial expiry');
console.log('PASS: payment at the exact trial-expiry timestamp does not qualify for the ₱100 discount');

const missingLedgerLock = lockBillingDecision(null, [], FIXED_NOW);
assert.equal(missingLedgerLock.allowed, false);
assert.equal(missingLedgerLock.reason, 'BILLING_SYNC_REQUIRED');

const trialLock = lockBillingDecision(firstTrial, [firstTrial, secondPending], FIXED_NOW);
assert.equal(trialLock.allowed, true, 'Active 24-hour trial should permit LOCK');

const pendingLock = lockBillingDecision(secondPending, [firstTrial, secondPending], FIXED_NOW);
assert.equal(pendingLock.allowed, false);
assert.equal(pendingLock.reason, 'PAYMENT_REQUIRED');

const expiredLock = lockBillingDecision(expiredFirst, [expiredFirst], FIXED_NOW);
assert.equal(expiredLock.allowed, false);
assert.equal(expiredLock.reason, 'PAYMENT_REQUIRED');

const activePaidChild = {
  ...firstTrial,
  billing_status: 'ACTIVE',
  trial_ends_at: null,
  paid_until: FIXED_NOW + 365 * 86_400_000,
  paid_amount: 300
};
assert.equal(lockBillingDecision(activePaidChild, [activePaidChild], FIXED_NOW).allowed, true);

const commandRouteStart = source.indexOf('if (commandMatch && method === "POST")');
const commandRouteEnd = source.indexOf('const commandAt = now();', commandRouteStart);
assert.ok(commandRouteStart >= 0 && commandRouteEnd > commandRouteStart, 'Command route boundaries must exist');
const guardedRoute = source.slice(commandRouteStart, commandRouteEnd);
assert.match(guardedRoute, /if \(command === "LOCK"\) \{[\s\S]*?lockBillingDecision/);
assert.match(source, /const unlockAt = command === "LOCK" \? requestedUnlockAt : 0;/,
  'UNLOCK must clear any scheduled Auto Unlock time');

const localSourcePath = path.join(__dirname, '..', '..', 'android', 'app', 'src', 'main', 'java', 'com', 'master', 'bedtime', 'LocalParentServer.java');
const localSource = fs.readFileSync(localSourcePath, 'utf8');
assert.match(localSource, /if \("LOCK"\.equals\(command\) && billingStatus\.trim\(\)\.isEmpty\(\)\)/,
  'Local LOCK must wait for billing sync when status is missing');

console.log('PASS: LOCK blocked when billing ledger is missing');
console.log('PASS: LOCK allowed during the active 24-hour trial');
console.log('PASS: LOCK blocked while payment is pending or expired');
console.log('PASS: LOCK allowed for a paid Child');
console.log('PASS: command route applies billing guard only to LOCK; UNLOCK bypasses it');
console.log('PASS: local LOCK waits for billing sync when status is missing');

const freshTrialWindow = initialChildTrialWindow(FIXED_NOW);
assert.equal(freshTrialWindow.trialStartedAt, FIXED_NOW);
assert.equal(freshTrialWindow.trialEndsAt, FIXED_NOW + 86_400_000);
assert.equal(freshTrialWindow.trialEndsAt - freshTrialWindow.trialStartedAt, 86_400_000);
assert.match(source, /const trialWindow = initialChildTrialWindow\(at\);/);
console.log('PASS: legacy Child billing registration starts a fresh 24-hour trial');
