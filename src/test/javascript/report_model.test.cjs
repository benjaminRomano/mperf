const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
vm.runInThisContext(
  fs.readFileSync(
    require("node:path").join(
      __dirname,
      "../../main/resources/faults-engine/shared/model.js",
    ),
    "utf8",
  ),
);
const event = (id, address, source = "a", page = 0) => ({
  id,
  address,
  source,
  page,
});
test("unplottable addresses retain records without bridging adjacent deltas", () => {
  const events = [
    event(1, "0x1000"),
    { ...event(2, "0xffffffffffffffff"), addressPlot: false },
    event(3, "0x3000"),
    event(4, "0x4000"),
  ];
  assert.equal(FaultModel.page(events[1], 4096), null);
  assert.equal(events[1].address, "0xffffffffffffffff");
  assert.deepEqual(
    FaultModel.deltas(events, 4096, "address").map((r) => [
      r.previous.id,
      r.event.id,
      r.value,
    ]),
    [[3, 4, 1]],
  );
});
test("page deltas retain address precision beyond Number.MAX_SAFE_INTEGER", () => {
  const events = [
    event(1, "0xfffffffff0001000"),
    event(2, "0xfffffffff0003001"),
    event(3, "0xfffffffff0000000"),
  ];
  assert.deepEqual(
    FaultModel.deltas(events, 4096, "address").map((r) => r.value),
    [2, -3],
  );
  assert.equal(FaultModel.deltas(events, 4096, "address")[0].previous.id, 1);
});
test("file deltas omit unknown and cross-file transitions, never bridge them", () => {
  const events = [
    event(1, "0x0", "a", 7),
    event(2, "0x0", "b", 9),
    event(3, "0x0", "b", 10),
    event(4, "0x0", "b", null),
    event(5, "0x0", "b", 12),
  ];
  assert.deepEqual(
    FaultModel.deltas(events, 4096, "file").map((r) => [r.event.id, r.value]),
    [[3, 1]],
  );
});
test("delta uses consecutive filtered events and has no invented first point", () => {
  const filtered = [event(2, "0x2000"), event(9, "0x6000")];
  assert.equal(FaultModel.deltas(filtered, 4096, "address")[0].value, 4);
  assert.deepEqual(FaultModel.deltas([], 4096, "address"), []);
  assert.deepEqual(
    FaultModel.deltas(filtered.slice(0, 1), 4096, "address"),
    [],
  );
});
test("median is the mean of the middle pair for even series", () => {
  assert.equal(FaultModel.median([1, 4]), 2.5);
  assert.equal(FaultModel.median([]), null);
});
test("region filters distinguish DEX payloads, archive entries and binary sections", () => {
  const dex = { detail: { dex: "classes2.dex", section: ".text" } };
  assert.deepEqual(FaultModel.regions(dex), [
    { value: "section:.text", label: "Section · .text" },
    { value: "dex:classes2.dex", label: "DEX · classes2.dex" },
  ]);
  assert.ok(FaultModel.matchesRegion(dex, "dex:classes2.dex"));
  assert.ok(!FaultModel.matchesRegion(dex, "section:classes2.dex"));
  assert.equal(
    FaultModel.regions({ detail: { dex: "resources.arsc" } })[0].label,
    "Entry · resources.arsc",
  );
  assert.ok(FaultModel.matchesRegion({}, ""));
});
