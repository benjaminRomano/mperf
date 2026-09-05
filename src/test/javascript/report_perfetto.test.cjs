const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
vm.runInThisContext(fs.readFileSync(require("node:path").join(__dirname,
  "../../main/resources/faults-engine/shared/perfetto.js"), "utf8"));

function fixture({ file = false, blocked = false, response = 200 } = {}) {
  const element = () => ({ addEventListener() {}, removeAttribute(key) { delete this[key]; } });
  const button = element(), download = element(), status = element();
  const listeners = new Map(), timers = new Map(), messages = [], fetches = [], opens = [];
  const popup = { closed: false, postMessage(...args) { messages.push(args); } };
  let id = 0;
  const host = {
    location: { href: file ? "file:///captures/report.html" : "http://localhost/captures/report.html", origin: "http://localhost" },
    open(...args) { opens.push(args); return blocked ? null : popup; },
    fetch(url) { fetches.push(url); return Promise.resolve({ ok: response === 200, status: response, arrayBuffer: async () => new ArrayBuffer(10) }); },
    addEventListener(type, callback) { listeners.set(type, callback); },
    removeEventListener(type, callback) { if (listeners.get(type) === callback) listeners.delete(type); },
    setInterval(callback) { timers.set(++id, callback); return id; },
    setTimeout(callback) { timers.set(++id, callback); return id; },
    clearInterval(key) { timers.delete(key); },
    clearTimeout(key) { timers.delete(key); },
  };
  const viewer = FaultPerfetto.create({ button, download, status, host });
  viewer.setRun({ label: "Run A", perfettoTrace: "a/faults.pftrace" });
  return { viewer, button, download, status, popup, listeners, timers, messages, fetches, opens };
}

test("only the opened Perfetto origin can receive the selected run trace", async () => {
  const f = fixture();
  const opening = f.viewer.open();
  f.listeners.get("message")({ source: f.popup, origin: "https://wrong.example", data: "PONG" });
  f.listeners.get("message")({ source: {}, origin: "https://ui.perfetto.dev", data: "PONG" });
  await Promise.resolve();
  assert.equal(f.messages.length, 0);
  f.listeners.get("message")({ source: f.popup, origin: "https://ui.perfetto.dev", data: "PONG" });
  await opening;
  assert.deepEqual(f.fetches, ["http://localhost/captures/a/faults.pftrace"]);
  assert.equal(f.messages[0][0].perfetto.title, "Run A · startup");
  assert.equal(f.messages[0][1], "https://ui.perfetto.dev");
  assert.equal(f.messages[0][2][0], f.messages[0][0].perfetto.buffer);
  assert.equal(f.timers.size, 0);
  assert.equal(f.listeners.has("message"), false);
  assert.equal(f.button.disabled, false);
});

test("switching runs cancels an in-flight open and updates the trace reference", async () => {
  const f = fixture();
  const opening = f.viewer.open();
  f.viewer.setRun({ label: "Run B", perfettoTrace: "../b/faults.pftrace" });
  await opening;
  assert.equal(f.messages.length, 0);
  assert.equal(f.download.href, "../b/faults.pftrace");
  assert.equal(f.status.hidden, true);
  assert.equal(f.button.disabled, false);
  f.viewer.setRun({ label: "No trace" });
  assert.equal(f.button.disabled, true);
  assert.equal(f.download.hidden, true);
  assert.equal(f.download.href, undefined);
});

test("file pages and blocked popups provide a manual fallback without fetching", async () => {
  for (const options of [{ file: true }, { blocked: true }]) {
    const f = fixture(options);
    await f.viewer.open();
    assert.equal(f.fetches.length, 0);
    assert.equal(f.status.hidden, false);
    assert.match(f.status.textContent, /open.*Perfetto/i);
    assert.equal(f.button.disabled, false);
  }
});

test("missing trace and readiness timeout release resources and allow retry", async () => {
  for (const response of [404, 200]) {
    const f = fixture({ response });
    const opening = f.viewer.open();
    if (response === 200) [...f.timers.values()].at(-1)();
    await opening;
    assert.match(f.status.textContent, response === 404 ? /HTTP 404/ : /60 seconds/);
    assert.equal(f.timers.size, 0);
    assert.equal(f.listeners.has("message"), false);
    assert.equal(f.button.disabled, false);
    assert.equal(f.messages.length, 0);
  }
});
