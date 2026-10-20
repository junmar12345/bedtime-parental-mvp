const express = require('express');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(express.json({ limit: '200kb' }));

const DATA_DIR = path.join(__dirname, 'data');
const STATE_FILE = path.join(DATA_DIR, 'state.json');
fs.mkdirSync(DATA_DIR, { recursive: true });

function loadState() {
  if (!fs.existsSync(STATE_FILE)) {
    return { parents: {}, children: {}, payments: {}, settings: { trialDays: 1, annualPrice: 300, paymentQrText: 'BEDTIME-PAYMENT-QR-PLACEHOLDER' } };
  }
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); }
  catch { return { parents: {}, children: {}, payments: {}, settings: { trialDays: 1, annualPrice: 300, paymentQrText: 'BEDTIME-PAYMENT-QR-PLACEHOLDER' } }; }
}
function saveState() { fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2), 'utf8'); }
let state = loadState();
if (!state.settings || typeof state.settings !== 'object') {
  state.settings = { trialDays: 1, annualPrice: 300, paymentQrText: 'BEDTIME-PAYMENT-QR-PLACEHOLDER' };
}
state.settings.trialDays = Number(state.settings.trialDays || 1);
state.settings.annualPrice = Number(state.settings.annualPrice || 300);

// Normalize persisted state so older installs remain compatible with new features.
for (const p of Object.values(state.parents || {})) {
  if (!p.recoveryCode) p.recoveryCode = 'BF-' + crypto.randomBytes(4).toString('hex').toUpperCase();
  if (!Array.isArray(p.children)) p.children = [];
}
for (const c of Object.values(state.children || {})) {
  if (typeof c.active !== 'boolean') c.active = true;
}
saveState();

const now = () => Date.now();
const iso = ms => new Date(ms).toISOString();
const id = prefix => `${prefix}-${crypto.randomBytes(4).toString('hex').toUpperCase()}`;
const token = prefix => `${prefix}_${crypto.randomBytes(18).toString('hex')}`;
function hashPassword(password) {
  return crypto.createHash('sha256').update(String(password)).digest('hex');
}
function randomCode() { return String(100000 + crypto.randomInt(900000)); }
function getParentByToken(t) { return Object.values(state.parents).find(p => p.token === t); }
function getChildByToken(t) { return Object.values(state.children).find(c => c.active !== false && c.token === t); }
function subscriptionStatus(p) {
  const t = now();
  if (p.subscriptionStatus === 'ACTIVE' && p.subscriptionEndsAt && t > p.subscriptionEndsAt) {
    p.subscriptionStatus = 'EXPIRED';
  }
  if (p.subscriptionStatus === 'TRIAL' && t > p.trialEndsAt) p.subscriptionStatus = 'EXPIRED';
  if ((p.subscriptionStatus === 'ACTIVE' || p.subscriptionStatus === 'TRIAL') && p.subscriptionEndsAt && p.subscriptionStatus === 'ACTIVE') {
    const msLeft = p.subscriptionEndsAt - t;
    if (msLeft <= 7 * 24 * 60 * 60 * 1000 && msLeft > 0) p.subscriptionStatus = 'NEAR_EXPIRY';
  }
  return p.subscriptionStatus;
}
function parentSummary(p) {
  const status = subscriptionStatus(p);
  return {
    parentId: p.parentId,
    name: p.name,
    email: p.email,
    mobile: p.mobile || '',
    status,
    trialEndsAt: p.trialEndsAt ? iso(p.trialEndsAt) : null,
    subscriptionEndsAt: p.subscriptionEndsAt ? iso(p.subscriptionEndsAt) : null,
    paymentQrText: state.settings.paymentQrText,
    annualPrice: state.settings.annualPrice || 300
  };
}
function childSummary(c) {
  const online = c.lastSeenAt && (now() - c.lastSeenAt <= 45 * 1000);
  return {
    childId: c.childId,
    code: c.code,
    name: c.name,
    paired: Boolean(c.parentId),
    online,
    lastSeenAt: c.lastSeenAt ? iso(c.lastSeenAt) : null,
    locked: c.locked === true,
    active: c.active !== false
  };
}
function requireParent(req, res, next) {
  const p = getParentByToken(req.header('x-parent-token') || req.body?.parentToken);
  if (!p) return res.status(401).json({ error: 'Invalid Parent session' });
  req.parent = p; next();
}
function requireChild(req, res, next) {
  const c = getChildByToken(req.header('x-child-token') || req.body?.childToken);
  if (!c) return res.status(401).json({ error: 'Invalid Child session' });
  req.child = c; next();
}

app.get('/health', (_req, res) => res.json({ ok: true, service: 'bedtime-final-new-app' }));

app.get('/', (_req, res) => {
  res.type('html').send(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Bedtime Parent Registration</title>
<style>body{font-family:Arial;background:#111827;color:#fff;min-height:100vh;display:grid;place-items:center;margin:0}.card{width:min(92vw,480px);background:#1f2937;padding:28px;border-radius:20px}input{width:100%;box-sizing:border-box;padding:13px;margin:7px 0 14px;border-radius:10px;border:1px solid #4b5563;background:#111827;color:#fff}button{width:100%;padding:14px;border:0;border-radius:10px;font-weight:700;cursor:pointer}.note{color:#9ca3af;font-size:13px;line-height:1.5}#out{margin-top:16px;white-space:pre-wrap}</style></head>
<body><div class="card"><h1>🌙 Bedtime Parental</h1><h2>Parent Registration</h2><p class="note">Register first. A new Parent receives a 1-day free trial with 1 child included.</p>
<label>Full name</label><input id="name"><label>Email</label><input id="email" type="email"><label>Password</label><input id="password" type="password"><label>Mobile</label><input id="mobile"><button onclick="reg()">REGISTER PARENT</button><div id="out"></div></div>
<script>async function reg(){const out=document.getElementById('out');out.textContent='Saving...';const b={name:document.getElementById('name').value.trim(),email:document.getElementById('email').value.trim(),password:document.getElementById('password').value,mobile:document.getElementById('mobile').value.trim()};try{const r=await fetch('/api/register-parent',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)});const d=await r.json();if(!r.ok)throw Error(d.error||'Registration failed');out.textContent='✓ Registration saved\\nParent ID: '+d.parent.parentId+'\\nTrial: 1 day FREE\\n\\nYour 1-day free trial is active. Continue in the Bedtime app.'}catch(e){out.textContent='Error: '+e.message}}</script></body></html>`);
});

app.post('/api/register-parent', (req, res) => {
  const { name, email, password, mobile } = req.body || {};
  if (!name || !email || !password) return res.status(400).json({ error: 'Name, email, and password are required' });
  const emailKey = String(email).trim().toLowerCase();
  if (Object.values(state.parents).some(p => p.email === emailKey)) return res.status(409).json({ error: 'Email already registered' });
  const parentId = id('P');
  const activatedAt = now();
  const p = { parentId, name: String(name).trim(), email: emailKey, mobile: String(mobile || '').trim(), passwordHash: hashPassword(password), token: null, recoveryCode: 'BF-' + crypto.randomBytes(4).toString('hex').toUpperCase(), activatedAt, trialEndsAt: activatedAt + 1 * 24 * 60 * 60 * 1000, subscriptionStatus: 'TRIAL', subscriptionEndsAt: null, children: [] };
  state.parents[parentId] = p; saveState();
  res.json({ parent: { parentId, name: p.name, email: p.email, status: p.subscriptionStatus, recoveryCode: p.recoveryCode } });
});

app.post('/api/login', (req, res) => {
  const { email, password } = req.body || {};
  const p = Object.values(state.parents).find(x => x.email === String(email || '').trim().toLowerCase());
  if (!p || p.passwordHash !== hashPassword(password || '')) return res.status(401).json({ error: 'Invalid email or password' });
  if (!p.activatedAt) {
    p.activatedAt = now();
    p.trialEndsAt = p.activatedAt + state.settings.trialDays * 24 * 60 * 60 * 1000;
    p.subscriptionStatus = 'TRIAL';
  }
  if (!p.token) p.token = token('parent');
  saveState();
  res.json({ token: p.token, parent: parentSummary(p) });
});

app.get('/api/me', requireParent, (req, res) => { saveState(); res.json({ parent: parentSummary(req.parent) }); });

app.post('/api/child/register', (req, res) => {
  const name = String(req.body?.name || 'Child').trim() || 'Child';
  const childId = id('C');
  let code; do { code = randomCode(); } while (Object.values(state.children).some(c => c.code === code && !c.parentId));
  const c = { childId, name, code, token: token('child'), parentId: null, lastSeenAt: now(), locked: false, command: 'UNLOCK', active: true, createdAt: now() };
  state.children[childId] = c; saveState();
  res.json({ child: { childId, name, code }, token: c.token });
});

app.post('/api/pair', requireParent, (req, res) => {
  const code = String(req.body?.code || '').trim();
  if (!/^\d{6}$/.test(code)) return res.status(400).json({ error: 'Child code must be 6 digits' });
  const c = Object.values(state.children).find(x => x.code === code && x.active !== false);
  if (!c) return res.status(404).json({ error: 'Child code not found' });
  if (c.parentId && c.parentId !== req.parent.parentId) return res.status(409).json({ error: 'Child is already paired' });
  c.parentId = req.parent.parentId;
  if (!req.parent.children.includes(c.childId)) req.parent.children.push(c.childId);
  saveState(); res.json({ ok: true, child: childSummary(c) });
});

app.get('/api/children', requireParent, (req, res) => {
  const children = req.parent.children.map(cid => state.children[cid]).filter(c => c && c.active !== false).map(childSummary);
  saveState(); res.json({ children });
});

app.post('/api/children/:childId/command', requireParent, (req, res) => {
  const c = state.children[req.params.childId];
  if (!c || c.parentId !== req.parent.parentId) return res.status(404).json({ error: 'Child not found' });
  const command = String(req.body?.command || '').toUpperCase();
  if (!['LOCK', 'UNLOCK'].includes(command)) return res.status(400).json({ error: 'Command must be LOCK or UNLOCK' });
  c.command = command; c.locked = command === 'LOCK'; saveState();
  res.json({ ok: true, command, child: childSummary(c) });
});

app.delete('/api/children/:childId', requireParent, (req, res) => {
  const c = state.children[req.params.childId];
  if (!c || c.parentId !== req.parent.parentId || c.active === false) {
    return res.status(404).json({ error: 'Child not found' });
  }
  const online = Boolean(c.lastSeenAt && (now() - c.lastSeenAt <= 45 * 1000));
  if (online) return res.status(409).json({ error: 'Only offline children can be removed' });

  req.parent.children = req.parent.children.filter(x => x !== c.childId);
  c.deletedAt = now();
  c.deletedFromParentId = req.parent.parentId;
  c.active = false;
  c.parentId = null;
  c.token = null;
  c.command = 'UNLOCK';
  c.locked = false;
  saveState();
  res.json({ ok: true, deleted: true, childId: c.childId });
});

app.post('/api/child/heartbeat', requireChild, (req, res) => {
  req.child.lastSeenAt = now();
  req.child.locked = (req.child.command || 'UNLOCK') === 'LOCK';
  saveState();
  res.json({ ok: true, command: req.child.command || 'UNLOCK', locked: req.child.locked, parentId: req.child.parentId });
});

app.post('/api/child/unpair', requireChild, (req, res) => {
  const c = req.child;
  const oldParentId = c.parentId;
  if (oldParentId && state.parents[oldParentId]) {
    state.parents[oldParentId].children = state.parents[oldParentId].children.filter(x => x !== c.childId);
  }
  c.parentId = null;
  c.command = 'UNLOCK';
  c.locked = false;
  c.lastSeenAt = now();
  saveState();
  res.json({ ok: true, child: childSummary(c) });
});

app.get('/api/child/command', requireChild, (req, res) => {
  req.child.lastSeenAt = now(); saveState();
  res.json({ command: req.child.command, locked: req.child.locked, parentPaired: Boolean(req.child.parentId) });
});

app.get('/api/parent/recovery-code', requireParent, (req, res) => {
  if (!req.parent.recoveryCode) {
    req.parent.recoveryCode = 'BF-' + crypto.randomBytes(4).toString('hex').toUpperCase();
    saveState();
  }
  res.json({ recoveryCode: req.parent.recoveryCode });
});

app.post('/api/parent/change-password', requireParent, (req, res) => {
  const currentPassword = String(req.body?.currentPassword || '');
  const newPassword = String(req.body?.newPassword || '');
  if (newPassword.length < 8) return res.status(400).json({ error: 'New password must be at least 8 characters' });
  if (req.parent.passwordHash !== hashPassword(currentPassword)) return res.status(401).json({ error: 'Current password is incorrect' });
  req.parent.passwordHash = hashPassword(newPassword);
  req.parent.token = token('parent');
  saveState();
  res.json({ ok: true, token: req.parent.token });
});

app.post('/api/parent/forgot-password', (req, res) => {
  const email = String(req.body?.email || '').trim().toLowerCase();
  const mobile = String(req.body?.mobile || '').trim();
  const recoveryCode = String(req.body?.recoveryCode || '').trim().toUpperCase();
  const newPassword = String(req.body?.newPassword || '');
  const p = Object.values(state.parents).find(x => x.email === email);
  if (!p || p.recoveryCode !== recoveryCode) return res.status(400).json({ error: 'Email or recovery code is incorrect' });
  if (p.mobile && mobile !== p.mobile) return res.status(400).json({ error: 'Mobile number is incorrect' });
  if (newPassword.length < 8) return res.status(400).json({ error: 'New password must be at least 8 characters' });
  p.passwordHash = hashPassword(newPassword);
  p.token = null;
  saveState();
  res.json({ ok: true });
});

app.post('/api/payment/submit', requireParent, (req, res) => {
  const paymentId = id('PAY');
  const payment = { paymentId, parentId: req.parent.parentId, amount: Number(state.settings.annualPrice || 300), status: 'PENDING', createdAt: now() };
  state.payments[paymentId] = payment; saveState();
  res.json({ ok: true, payment: { paymentId, amount: payment.amount, status: payment.status } });
});

app.get('/api/payment/qr', requireParent, (_req, res) => res.json({ qrText: state.settings.paymentQrText, annualPrice: state.settings.annualPrice || 300 }));

app.post('/api/admin/payments/:paymentId/approve', (req, res) => {
  const payment = state.payments[req.params.paymentId];
  if (!payment) return res.status(404).json({ error: 'Payment not found' });
  const p = state.parents[payment.parentId];
  if (!p) return res.status(404).json({ error: 'Parent not found' });
  payment.status = 'APPROVED'; payment.approvedAt = now();
  p.subscriptionStatus = 'ACTIVE'; p.subscriptionEndsAt = now() + 365 * 24 * 60 * 60 * 1000;
  saveState(); res.json({ ok: true, payment: { paymentId: payment.paymentId, status: payment.status }, parent: parentSummary(p) });
});

app.get('/api/admin/payments', (_req, res) => res.json({ payments: Object.values(state.payments).map(x => ({ ...x, createdAt: iso(x.createdAt), approvedAt: x.approvedAt ? iso(x.approvedAt) : null })) }));

const port = Number(process.env.PORT || 8080);
app.listen(port, '0.0.0.0', () => console.log(`Bedtime FINAL CLEAN backend listening on :${port}`));
