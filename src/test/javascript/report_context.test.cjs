const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs"),
  vm = require("node:vm"),
  path = require("node:path");
vm.runInThisContext(
  fs.readFileSync(
    path.join(
      __dirname,
      "../../main/resources/faults-engine/shared/context.js",
    ),
    "utf8",
  ),
);
test("I/O lanes preserve distinct operations and never pair requests or complete unfinished spans", () => {
  const run = {
    events: [
      {
        id: 0,
        time: 1,
        major: true,
        fileBacked: true,
        source: "<file>",
        thread: "main",
      },
      { time: 2, major: true, fileBacked: false },
    ],
    io: {
      advice: [
        { time: 0, end: 2, name: "madvising dex", incomplete: false },
        { time: 3, end: null, name: "unfinished", incomplete: true },
      ],
      blocking: [],
      cache: [{ time: 1, page_count: 4, file_name: "dex" }],
      block: [
        { time: 1, event: "block_rq_issue", sector: 10 },
        { time: 2, event: "block_rq_complete", sector: 10 },
        { time: 3, event: "block_rq_remap" },
      ],
    },
  };
  const traces = FaultContext.traces(run);
  assert.deepEqual(traces.find((t) => t.name === "Major faults").x, [1]);
  assert.ok(
    traces
      .find((t) => t.name === "Major faults")
      .text[0].includes("&lt;file&gt;"),
  );
  assert.deepEqual(
    traces.find((t) => t.mode === "lines" && t.y[0] === "ART advice").x,
    [0, 2, null],
  );
  assert.deepEqual(traces.find((t) => t.name === "ART advice").x, [0, 3]);
  for (const lane of ["Block issue", "Block complete", "Other block events"])
    assert.equal(traces.find((t) => t.name === lane).x.length, 1);
  assert.equal(
    traces.find((t) => t.name === "File-cache insertions").x.length,
    1,
  );
});
