/* Whole-startup context: no temporal join is treated as a causal relationship. */
globalThis.FaultContext = (() => {
  const lanes = [
    "Major faults",
    "Minor faults",
    "File-cache insertions",
    "ART advice",
    "App blocking",
    "Block issue",
    "Block complete",
    "Other block events",
  ];
  function traces(run, type = "scatter") {
    const io = run.io || {},
      result = [];
    const esc = (value) =>
      String(value ?? "").replace(
        /[&<>"']/g,
        (c) =>
          ({
            "&": "&amp;",
            "<": "&lt;",
            ">": "&gt;",
            '"': "&quot;",
            "'": "&#39;",
          })[c],
      );
    function points(name, rows, color, symbol, text) {
      result.push({
        type,
        mode: "markers",
        name,
        x: rows.map((r) => r.time),
        y: rows.map(() => name),
        text: rows.map((r) => esc(text(r))),
        hovertemplate: "%{x:.3f} ms<br>%{text}<extra>" + name + "</extra>",
        marker: { color, symbol, size: 6 },
        showlegend: false,
      });
    }
    for (const major of [true, false])
      points(
        major ? lanes[0] : lanes[1],
        run.events.filter((e) => e.fileBacked && e.major === major),
        major ? "#b86b12" : "#3973b9",
        major ? "diamond" : "circle",
        (r) => `#${r.id} · ${r.source} · ${r.thread}`,
      );
    points(
      lanes[2],
      io.cache || [],
      "#3973b9",
      "circle-open",
      (r) =>
        `${r.file_name || "Unresolved file"} · ${r.page_count} pages · ${r.process_name} / ${r.thread_name} · device ${r.device}, inode ${r.inode}`,
    );
    for (const [name, rows, color] of [
      [lanes[3], io.advice || [], "#3973b9"],
      [lanes[4], io.blocking || [], "#b86b12"],
    ]) {
      const complete = rows.filter((r) => Number.isFinite(r.end)),
        x = [],
        y = [];
      for (const r of complete) {
        x.push(r.time, r.end, null);
        y.push(name, name, null);
      }
      result.push({
        type: "scatter",
        mode: "lines",
        x,
        y,
        line: { width: 9, color },
        hoverinfo: "skip",
        showlegend: false,
      });
      points(
        name,
        rows,
        color,
        "square",
        (r) =>
          `${r.name || r.state} · ${r.thread_name} (${r.tid}) · ${r.incomplete ? "incomplete span" : (r.end - r.time).toFixed(3) + " ms overlap"} · io_wait=${r.io_wait ?? "n/a"} · ${r.blocked_function || ""}`,
      );
    }
    for (const [name, predicate] of [
      [lanes[5], (e) => /_(issue|start)$/.test(e)],
      [lanes[6], (e) => /_(complete|done)$/.test(e)],
      [lanes[7], (e) => !/_(issue|start|complete|done)$/.test(e)],
    ]) {
      points(
        name,
        (io.block || []).filter((r) => predicate(r.event)),
        "#59616c",
        "cross",
        (r) =>
          `${r.event} · ${r.rwbs || "unknown operation"} · ${r.bytes || "unknown"} bytes · device ${r.device} sector ${r.sector} · ${r.thread_name || "worker"}`,
      );
    }
    return result;
  }
  return { lanes, traces };
})();
