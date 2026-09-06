const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

test("health-only report starts on diagnostics and cannot activate fault plots", () => {
  const elements = new Map();
  const classes = new Map();
  function element(id = "") {
    return {
      value: "0",
      hidden: false,
      dataset: { tab: id.replace("tab-", "") },
      style: {},
      classList: { remove() {}, toggle() {} },
      setAttribute() {},
      addEventListener() {},
      appendChild() {},
      getContext() {
        return null;
      },
    };
  }
  const get = (id) => {
    if (!elements.has(id)) elements.set(id, element(id));
    return elements.get(id);
  };
  const tabs = ["health", "io", "pages", "stacks", "flame", "sites"].map(
    (tab) => get("tab-" + tab),
  );
  const context = vm.createContext({
    REPORT: {
      runs: [
        {
          label: "Failed",
          healthOnly: true,
          events: [],
          sources: {},
          subtitle: "No validated fault analysis",
          cache: "Unknown",
          provenance: { capture_status: "failed" },
          health: [
            {
              name: "Failure",
              state: "warning",
              value: "<unsafe>",
              action: "Inspect logs",
            },
          ],
        },
      ],
    },
    document: {
      getElementById: get,
      createElement: element,
      querySelector: get,
      querySelectorAll: () => tabs,
      body: {
        classList: {
          toggle: (name, value) => classes.set(name, value),
          remove() {},
        },
      },
    },
    window: { addEventListener() {} },
    FaultPerfetto: { create: () => ({ setRun() {} }) },
    FaultStacks: { create: () => ({}) },
    Plotly: {
      react() {
        assert.fail("Health-only mode must not draw fault plots");
      },
    },
    ResizeObserver: class {
      observe() {}
    },
  });
  vm.runInContext(
    fs.readFileSync(
      path.join(
        __dirname,
        "../../main/resources/faults-engine/shared/report.js",
      ),
      "utf8",
    ),
    context,
  );
  assert.equal(classes.get("health-only"), true);
  assert.equal(get("panel-health").hidden, false);
  assert.equal(get("summary").textContent, "No validated fault analysis");
  assert.match(get("healthRows").innerHTML, /&lt;unsafe&gt;/);
  for (const tab of ["io", "pages", "stacks", "flame", "sites"])
    assert.equal(get("tab-" + tab).hidden, true);
  for (const tab of ["pages", "stacks", "sites", "io"]) {
    vm.runInContext(`setTab("${tab}"); update();`, context);
    assert.equal(get("panel-health").hidden, false);
    assert.equal(get("panel-pages").hidden, true);
    assert.equal(get("panel-stacks").hidden, true);
  }
});
