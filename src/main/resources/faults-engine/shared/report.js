/* Offline fault reader. Stack data stays leaf-first; no inferred callers. */
"use strict";
// Expand dictionary references once; events sharing a stack retain their own identity.
for (const run of REPORT.runs) {
  if (!run.stacks) continue;
  const stacks = run.stacks.map((indices) =>
    indices.map((index) => run.frames[index]),
  );
  for (const event of run.events) event.stack = stacks[event.stackId] || [];
  delete run.stacks;
  delete run.frames;
}
const $ = (id) => document.getElementById(id);
const perfetto = FaultPerfetto.create({
  button: $("openPerfetto"),
  download: $("downloadTrace"),
  status: $("perfettoStatus"),
});
const escapeHtml = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const fmt = (n) => Number(n).toLocaleString();
const MAJOR = "#b86b12",
  MINOR = "#3973b9";
const config = {
  responsive: true,
  displaylogo: false,
  scrollZoom: false,
  modeBarButtonsToRemove: ["lasso2d", "select2d"],
};
let run,
  events = [],
  sourceEvents = [],
  selected = null,
  activeTab = "pages",
  range = null;
let plotType = "scatter";
try {
  const canvas = document.createElement("canvas");
  if (canvas.getContext("webgl2") || canvas.getContext("webgl"))
    plotType = "scattergl";
} catch (_) {
  /* SVG works without WebGL. */
}
function option(select, value, label) {
  const node = document.createElement("option");
  node.value = value;
  node.textContent = label;
  select.appendChild(node);
}
function sourceInfo(key) {
  return run.sources[key] || { label: key, path: key, boundaries: [] };
}
function sourceLabel(key) {
  const label = sourceInfo(key).label;
  if (!label.startsWith("/")) return label;
  const parts = label.split("/"),
    name = parts.at(-1);
  const duplicates = Object.values(run.sources).filter(
    (s) => s.path.split("/").at(-1) === name,
  ).length;
  return duplicates > 1 ? parts.slice(-2).join("/") : name;
}
function sourceNoun() {
  return run.stacksOnly ? "Instruction binary" : "Read source";
}
function clearDetail() {
  selected = null;
  setDetailOpen(false);
  $("detail").textContent =
    "Click a point or chronological stack column to inspect a fault.";
}
function layout(ytitle, height = 550) {
  return {
    height,
    margin: { l: 105, r: 25, t: 35, b: 60 },
    font: {
      family: "-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif",
      size: 12,
      color: "#20242b",
    },
    paper_bgcolor: "white",
    plot_bgcolor: "white",
    hoverlabel: { namelength: -1 },
    xaxis: {
      title: { text: "Elapsed startup time (ms)" },
      gridcolor: "#e5e8ec",
      zeroline: false,
    },
    yaxis: {
      title: { text: ytitle },
      gridcolor: "#e5e8ec",
      zeroline: false,
      automargin: true,
    },
    legend: { orientation: "h", x: 0, y: 1.08 },
    showlegend: true,
  };
}
const stackReader = FaultStacks.create({
  canvas: $("stacks"),
  scroll: $("stackScroll"),
  tooltip: $("tooltip"),
  onSelect: selectFault,
  onFocus: (label, count) => {
    $("stackCount").textContent = label
      ? "Focused: " + label + " · " + fmt(count) + " faults"
      : "All captured paths";
  },
});
function setTab(tab) {
  if (run.stacksOnly && tab === "pages") tab = "stacks";
  activeTab = tab;
  for (const button of document.querySelectorAll("[data-tab]"))
    button.setAttribute("aria-selected", String(button.dataset.tab === tab));
  $("panel-pages").hidden = tab !== "pages";
  $("panel-stacks").hidden = tab !== "stacks" && tab !== "flame";
  $("panel-stacks").setAttribute(
    "aria-labelledby",
    tab === "flame" ? "tab-flame" : "tab-stacks",
  );
  $("panel-sites").hidden = tab !== "sites";
  if (tab === "pages") drawAccess();
  if (tab === "stacks" || tab === "flame") drawStacks();
  if (tab === "sites") drawSites();
}
function changeRun() {
  run = REPORT.runs[Number($("run").value)];
  perfetto.setRun(run);
  range = null;
  clearDetail();
  run.events.forEach((e, i) => (e.order = i + 1));
  for (const id of ["source", "thread", "section"]) $(id).replaceChildren();
  option($("source"), "", "All sources");
  option($("thread"), "", "All threads");
  option($("section"), "", "All regions");
  Object.keys(run.sources).forEach((key) =>
    option($("source"), key, sourceInfo(key).label),
  );
  [...new Set(run.events.map((e) => e.thread))]
    .sort()
    .forEach((t) => option($("thread"), t, t));
  [
    ...new Map(
      run.events
        .flatMap(FaultModel.regions)
        .map((region) => [region.value, region.label]),
    ),
  ]
    .sort((a, b) => a[1].localeCompare(b[1]))
    .forEach(([value, label]) => option($("section"), value, label));
  $("sectionFilter").hidden =
    Boolean(run.stacksOnly) || $("section").options.length === 1;
  $("tab-pages").hidden = Boolean(run.stacksOnly);
  $("siteSourceTitle").textContent = sourceNoun();
  const majors = run.events.filter((e) => e.major),
    mapped = majors.filter((e) => e.offset !== null);
  $("summary").textContent =
    run.subtitle +
    " · " +
    fmt(majors.length) +
    " major · " +
    fmt(run.events.length - majors.length) +
    " minor" +
    (run.stacksOnly
      ? " · no read-address attribution"
      : " · " +
        fmt(mapped.length) +
        "/" +
        fmt(majors.length) +
        " major read files identified");
  $("cacheStatus").textContent = run.cache;
  $("sourceTitle").textContent = run.stacksOnly
    ? "Faulting binaries"
    : "Fault sources";
  $("notes").innerHTML = run.notes
    .map((n) => "<li>" + escapeHtml(n) + "</li>")
    .join("");
  $("provenance").textContent = JSON.stringify(run.provenance, null, 2);
  if (run.stacksOnly && activeTab === "pages") activeTab = "stacks";
  update();
  setTab(activeTab);
}
function update() {
  const kind = $("kind").value,
    source = $("source").value,
    thread = $("thread").value;
  const section = $("section").value,
    query = $("search").value.trim().toLowerCase();
  sourceEvents = run.events.filter(
    (e) =>
      (!run.fileBackedOnly || e.fileBacked) &&
      (kind === "all" || (kind === "major") === e.major) &&
      (!thread || e.thread === thread) &&
      FaultModel.matchesRegion(e, section) &&
      (!range || (e[range.field] >= range.lo && e[range.field] <= range.hi)) &&
      (!query ||
        JSON.stringify([
          sourceInfo(e.source).path,
          sourceInfo(e.source).label,
          e.stack,
          e.detail,
        ])
          .toLowerCase()
          .includes(query)),
  );
  events = sourceEvents.filter((e) => !source || e.source === source);
  syncFileViews();
  $("selectionCount").textContent =
    fmt(events.length) +
    " matching faults / " +
    fmt(run.events.length) +
    " captured" +
    (run.fileBackedOnly
      ? " · file-backed only; anonymous and unknown mappings hidden"
      : "");
  $("rangeStatus").textContent = range
    ? (range.field === "time" ? "Time: " : "Recorded index: ") +
      range.lo.toFixed(2) +
      "–" +
      range.hi.toFixed(2) +
      (range.field === "time" ? " ms" : "")
    : "";
  $("clearRange").hidden = !range;
  $("selectedSource").textContent = source
    ? sourceNoun() + ": " + sourceInfo(source).path
    : "All " + (run.stacksOnly ? "instruction binaries" : "read sources");
  $("selectedSource").title = $("selectedSource").textContent;
  stackReader.resetFocus();
  if (selected && !events.some((e) => e.id === selected.id)) clearDetail();
  drawSources();
  setTab(activeTab);
}
function syncFileViews() {
  const source = $("source").value;
  const allowed =
    !run.stacksOnly &&
    Boolean(source) &&
    run.events.some((e) => e.source === source && e.page !== null);
  for (const [value, label] of [
    ["offset", "File page index"],
    ["delta-file", "Δ file page"],
  ]) {
    const option = $("view").querySelector(`option[value="${value}"]`);
    option.disabled = !allowed;
    option.textContent = label + (allowed ? "" : " (select one file)");
    option.title = allowed
      ? ""
      : "File coordinates require one selected file with known offsets";
  }
  if (!allowed && ["offset", "delta-file"].includes($("view").value))
    $("view").value = "lanes";
}
function drawSources() {
  const counts = new Map();
  for (const e of sourceEvents) {
    const count = counts.get(e.source) || { major: 0, minor: 0 };
    count[e.major ? "major" : "minor"]++;
    counts.set(e.source, count);
  }
  const ranked = [...counts].sort(
    (a, b) =>
      b[1].major - a[1].major ||
      b[1].minor - a[1].minor ||
      a[0].localeCompare(b[0]),
  );
  $("sourceNote").textContent =
    fmt(ranked.length) + " sources · filtered counts, major-first";
  $("sources").innerHTML =
    ranked
      .map(
        ([key, c]) =>
          "<tr" +
          (key === $("source").value ? ' class="selected"' : "") +
          '><td><button data-source="' +
          escapeHtml(key) +
          '" title="' +
          escapeHtml(sourceInfo(key).path) +
          '">' +
          escapeHtml(sourceLabel(key)) +
          "</button></td><td>" +
          fmt(c.major) +
          "</td><td>" +
          fmt(c.minor) +
          "</td></tr>",
      )
      .join("") || '<tr><td colspan="3">No matching sources</td></tr>';
}
function drawAccess() {
  if (run.stacksOnly || activeTab !== "pages") return;
  syncFileViews();
  const mode = $("view").value,
    order = $("axis").value === "order",
    delta = mode.startsWith("delta-");
  const keys = [...new Set(events.map((e) => e.source))];
  const rows = delta
    ? FaultModel.deltas(
        events,
        run.pageSize,
        mode === "delta-file" ? "file" : "address",
      )
    : events
        .filter((e) => mode !== "offset" || e.page !== null)
        .filter(
          (e) =>
            mode !== "address" || FaultModel.page(e, run.pageSize) !== null,
        )
        .map((event) => ({ event }));
  const y = (r) =>
    delta
      ? r.value
      : mode === "lanes"
        ? r.event.source
        : mode === "offset"
          ? r.event.page
          : Number(FaultModel.page(r.event, run.pageSize));
  const traces = [false, true].map((major) => {
    const points = rows.filter((r) => r.event.major === major);
    return {
      type: plotType,
      mode: "markers",
      name: major ? "Major" : "Minor",
      x: points.map((r) => (order ? r.event.order : r.event.time)),
      y: points.map(y),
      customdata: points.map((r) => r.event.id),
      text: points.map((r) => {
        const e = r.event;
        return (
          escapeHtml(sourceInfo(e.source).path) +
          "<br>#" +
          e.order +
          " · " +
          e.time.toFixed(3) +
          " ms · " +
          escapeHtml(e.address) +
          (e.page === null ? "" : " · file page " + e.page) +
          (delta
            ? "<br>Δ " + fmt(r.value) + " pages from #" + r.previous.order
            : "") +
          "<br>" +
          escapeHtml(e.detail.dex || e.detail.section || "")
        );
      }),
      marker: {
        color: major ? MAJOR : MINOR,
        size: major ? 7 : 4,
        symbol: major ? "diamond" : "circle",
        opacity: major ? 0.9 : 0.45,
      },
      hovertemplate: "%{text}<extra>%{fullData.name}</extra>",
    };
  });
  const title = delta
    ? mode === "delta-file"
      ? "Δ file page"
      : "Δ virtual page"
    : mode === "offset"
      ? "File page (" + run.pageSize / 1024 + " KiB)"
      : mode === "address"
        ? "Virtual page address"
        : "";
  const l = layout(
    title,
    Math.max(180, document.querySelector(".plot-scroll").clientHeight),
  );
  l.xaxis.title.text = order
    ? "Recorded fault index"
    : "Elapsed startup time (ms)";
  if (order) l.xaxis.tickformat = ",d";
  if (mode === "lanes") {
    l.yaxis = {
      tickvals: keys,
      ticktext: keys.map((k) => {
        const s = sourceLabel(k);
        return s.length > 29 ? s.slice(0, 12) + "…" + s.slice(-16) : s;
      }),
      categoryorder: "array",
      categoryarray: keys.slice().reverse(),
      range: [-0.5, keys.length - 0.5],
      tickfont: { size: 11 },
      automargin: false,
    };
    l.margin.l = 180;
    l.height = Math.max(l.height, keys.length * 22 + 100);
    l.legend.y = 1;
    l.legend.yanchor = "bottom";
  }
  if (mode === "address" && rows.length) {
    let lo = Infinity,
      hi = -Infinity;
    for (const r of rows) {
      lo = Math.min(lo, y(r));
      hi = Math.max(hi, y(r));
    }
    const ticks = [
      ...new Set(
        Array.from({ length: 6 }, (_, i) =>
          Math.round(lo + ((hi - lo) * i) / 5),
        ),
      ),
    ];
    l.yaxis.tickvals = ticks;
    l.yaxis.ticktext = ticks.map(
      (t) => "0x" + (BigInt(t) * BigInt(run.pageSize)).toString(16),
    );
  }
  l.shapes = [];
  l.annotations = [];
  if (delta) {
    l.yaxis.zeroline = true;
    l.yaxis.zerolinecolor = "#8d97a5";
  }
  if (mode === "offset" && keys.length === 1) {
    const touched = new Set(rows.map((r) => r.event.detail.section));
    const boundaries = (sourceInfo(keys[0]).boundaries || [])
      .filter((b) => b.kind !== "section" || touched.has(b.label))
      .sort((a, b) => a.page - b.page);
    let lo = 0,
      hi = 1;
    for (const r of rows) {
      lo = Math.min(lo, r.event.page);
      hi = Math.max(hi, r.event.page);
    }
    for (const b of boundaries) {
      lo = Math.min(lo, b.page);
      hi = Math.max(hi, b.page);
    }
    const gap = ((hi - lo) * 16) / (l.height - l.margin.t - l.margin.b);
    let previous = -Infinity;
    for (const b of boundaries) {
      l.shapes.push({
        type: "line",
        xref: "paper",
        x0: 0,
        x1: 1,
        y0: b.page,
        y1: b.page,
        line: { color: "#ba3030", width: 1 },
      });
      if (b.page - previous < gap) continue;
      previous = b.page;
      l.annotations.push({
        xref: "paper",
        x: 1,
        y: b.page,
        xanchor: "right",
        yanchor: "bottom",
        text: escapeHtml(b.label),
        showarrow: false,
        font: { size: 11, color: "#9b2020" },
        bgcolor: "rgba(255,255,255,.9)",
      });
    }
  }
  $("access").style.height = l.height + "px";
  const notes = {
    address:
      "Page-aligned virtual addresses; hover gives the exact fault address. Virtual gaps are not storage distance.",
    lanes:
      "One lane per read source. Select a file to inspect its access order.",
    offset:
      "Whole-file page indices. Red lines mark verified DEX starts or touched binary sections. " +
      (keys.length > 1 ? "Offsets in different files are unrelated. " : "") +
      (events.length - rows.length) +
      " faults lack file offsets.",
    "delta-address":
      "Signed change from the previous matching fault, in " +
      run.pageSize / 1024 +
      " KiB virtual pages. Filtering recomputes the previous fault; the first has no delta.",
    "delta-file":
      "Signed file-page change from the previous matching fault. First, unknown-offset, and cross-file transitions are omitted.",
  };
  $("accessNote").textContent =
    notes[mode] +
    ((mode === "address" || mode === "delta-address") &&
    events.some((e) => e.addressPlot === false)
      ? " " +
        events.filter((e) => e.addressPlot === false).length +
        " all-ones address events excluded from this plot, retained in counts and stacks. Deltas do not bridge them."
      : "") +
    (order
      ? " Indices retain their original positions when minor faults are hidden."
      : "");
  $("locality").textContent = run.fileBackedOnly
    ? "File-backed minor faults include cache hits and copy-on-write; counts alone do not measure readahead."
    : "Minor includes cache hits, anonymous allocation, and copy-on-write; this view alone cannot measure readahead efficacy.";
  if (keys.length === 1) {
    const pages = [
      ...new Set(events.filter((e) => e.page !== null).map((e) => e.page)),
    ].sort((a, b) => a - b);
    const median = FaultModel.median(
      FaultModel.deltas(events, run.pageSize, "file").map((r) =>
        Math.abs(r.value),
      ),
    );
    if (pages.length)
      $("locality").textContent =
        fmt(pages.length) +
        " distinct file pages in a " +
        fmt(pages.at(-1) - pages[0] + 1) +
        "-page range." +
        (median === null ? "" : " Median step: " + fmt(median) + " pages.") +
        " Locality evidence, not predicted time savings.";
  }
  Plotly.react("access", traces, l, config).then(() => {
    $("access").removeAllListeners("plotly_click");
    $("access").on("plotly_click", (ev) =>
      selectFault(events.find((e) => e.id === ev.points[0].customdata)),
    );
  });
}
function drawStacks() {
  if (activeTab !== "stacks" && activeTab !== "flame") return;
  const flame = activeTab === "flame",
    captured = events.filter((e) => e.stack.length).length;
  $("stackCoverage").textContent =
    fmt(captured) +
    "/" +
    fmt(events.length) +
    " faults have captured stacks. " +
    (flame
      ? "Callers at top; faulting frames below. Pinch to zoom; WASD pans. Click to inspect; double-click to focus. Escape resets."
      : "Callers at top; faulting frames below. Pinch to zoom; scroll or WASD to pan. Click to inspect. Escape resets.") +
    (!captured && !run.stacksOnly && REPORT.runs.some((r) => r.stacksOnly)
      ? " Choose the DWARF stacks run for the independent stream."
      : "");
  $("stackCount").textContent = flame
    ? "All captured paths"
    : fmt(events.length) + " faults · fully zoomed out";
  stackReader.render({
    events,
    sources: run.sources,
    stacksOnly: Boolean(run.stacksOnly),
    selectedId: selected?.id,
    mode: flame ? "flame" : "chronological",
  });
}
function selectFault(e) {
  if (!e) return;
  selected = e;
  setDetailOpen(true);
  const rows = {
    Fault:
      (e.major ? "Major" : "Minor") +
      " #" +
      e.order +
      " · " +
      e.time.toFixed(3) +
      " ms",
    [sourceNoun()]: sourceInfo(e.source).path,
    ...(run.stacksOnly
      ? {}
      : {
          Address: e.address,
          "File offset":
            e.offset === null
              ? "Not attributed"
              : "0x" + Number(e.offset).toString(16),
        }),
    Thread: e.thread,
    "Capture sequence": e.id,
    ...e.detail,
  };
  $("detail").innerHTML =
    '<dl class="detail-grid">' +
    Object.entries(rows)
      .filter(([, v]) => v !== null && v !== "")
      .map(
        ([k, v]) =>
          "<dt>" +
          escapeHtml(k) +
          "</dt><dd>" +
          (String(v).length > 500
            ? "<details><summary>Show " +
              escapeHtml(k) +
              "</summary><pre>" +
              escapeHtml(String(v).split("; ").join("\n")) +
              "</pre></details>"
            : escapeHtml(v)) +
          "</dd>",
      )
      .join("") +
    "</dl><h3>Captured stack · callers → faulting frame</h3>" +
    (e.stack.length
      ? '<ol class="stack-list">' +
        e.stack
          .slice()
          .reverse()
          .map(
            (f) =>
              '<li title="' +
              escapeHtml(f.file) +
              '">' +
              escapeHtml(f.label) +
              ' <span class="mono">' +
              escapeHtml(f.file) +
              "</span></li>",
          )
          .join("") +
        "</ol>"
      : "<p>No stack was captured for this fault.</p>");
  drawStacks();
}
function drawSites() {
  $("sites").innerHTML =
    events
      .map((e) => {
        const frame = e.stack.find((f) => !f.kind || f.kind === "user");
        return (
          "<tr><td>" +
          fmt(e.order) +
          "</td><td>" +
          (e.major ? "Major" : "Minor") +
          "</td><td>" +
          e.time.toFixed(3) +
          '</td><td title="' +
          escapeHtml(frame?.file) +
          '">' +
          '<button class="fault-link" data-fault="' +
          escapeHtml(e.id) +
          '">' +
          escapeHtml(frame?.label || "No captured user stack") +
          '</button></td><td title="' +
          escapeHtml(sourceInfo(e.source).path) +
          '">' +
          escapeHtml(sourceLabel(e.source)) +
          "</td></tr>"
        );
      })
      .join("") || '<tr><td colspan="5">No faults match the filters.</td></tr>';
}
$("sites").addEventListener("click", (ev) => {
  const button = ev.target.closest("[data-fault]");
  if (!button) return;
  selectFault(events.find((e) => String(e.id) === button.dataset.fault));
});
let detailHeightFraction = 0.34;
let detailDrag = null;
function syncDetailHeight(requested) {
  const panel = document.querySelector(".analysis-panel");
  const available =
    panel.clientHeight -
    panel.querySelector(".tabs").offsetHeight -
    $("selectedSource").offsetHeight;
  const size = FaultModel.detailSize(
    available,
    requested ?? panel.clientHeight * detailHeightFraction,
  );
  if (requested !== undefined && panel.clientHeight > 0)
    detailHeightFraction = size.height / panel.clientHeight;
  $("detailDock").style.setProperty("--detail-height", size.height + "px");
  const handle = $("resizeDetail");
  handle.setAttribute("aria-valuemin", size.min);
  handle.setAttribute("aria-valuemax", size.max);
  handle.setAttribute("aria-valuenow", size.height);
  handle.setAttribute("aria-valuetext", size.height + " pixels high");
  return size;
}
function stopDetailDrag(ev) {
  if (ev && detailDrag && ev.pointerId !== detailDrag.pointerId) return;
  detailDrag = null;
  $("resizeDetail").classList.remove("dragging");
  document.body.classList.remove("resizing-detail");
}
function setDetailOpen(open) {
  if (!open) stopDetailDrag();
  $("detailDock").classList.toggle("open", open);
  $("detail").hidden = !open;
  $("resizeDetail").hidden = !open;
  if (open) syncDetailHeight();
  $("toggleDetail").setAttribute("aria-expanded", String(open));
  $("detailCaption").textContent =
    (selected ? " · #" + fmt(selected.order) : "") +
    (open ? " · collapse" : " · expand");
}
$("toggleDetail").addEventListener("click", () =>
  setDetailOpen($("detail").hidden),
);
$("resizeDetail").addEventListener("pointerdown", (ev) => {
  if (ev.button !== 0 || !ev.isPrimary) return;
  ev.preventDefault();
  ev.currentTarget.focus();
  detailDrag = {
    pointerId: ev.pointerId,
    y: ev.clientY,
    height: $("detailDock").offsetHeight,
  };
  ev.currentTarget.setPointerCapture(ev.pointerId);
  ev.currentTarget.classList.add("dragging");
  document.body.classList.add("resizing-detail");
});
$("resizeDetail").addEventListener("pointermove", (ev) => {
  if (detailDrag?.pointerId === ev.pointerId)
    syncDetailHeight(detailDrag.height + detailDrag.y - ev.clientY);
});
for (const type of ["pointerup", "pointercancel", "lostpointercapture"])
  $("resizeDetail").addEventListener(type, stopDetailDrag);
$("resizeDetail").addEventListener("keydown", (ev) => {
  const size = syncDetailHeight();
  const heights = {
    ArrowUp: size.height + 24,
    ArrowDown: size.height - 24,
    Home: size.min,
    End: size.max,
  };
  if (!(ev.key in heights)) return;
  ev.preventDefault();
  syncDetailHeight(heights[ev.key]);
});
$("sources").addEventListener("click", (ev) => {
  const button = ev.target.closest("[data-source]");
  if (!button) return;
  $("source").value = button.dataset.source;
  if (activeTab === "pages") $("view").value = "offset";
  update();
});
$("allSources").addEventListener("click", () => {
  $("source").value = "";
  update();
});
document
  .querySelectorAll("[data-tab]")
  .forEach((button) =>
    button.addEventListener("click", () => setTab(button.dataset.tab)),
  );
REPORT.runs.forEach((r, i) => option($("run"), i, r.label));
$("run").addEventListener("change", changeRun);
for (const id of ["kind", "source", "thread", "section"])
  $(id).addEventListener("change", update);
$("search").addEventListener("input", update);
$("view").addEventListener("change", () => {
  if ($("view").value.startsWith("delta-")) $("axis").value = "order";
  drawAccess();
});
$("axis").addEventListener("change", drawAccess);
$("reset").addEventListener("click", () => {
  $("kind").value = "major";
  $("source").value = "";
  $("thread").value = "";
  $("section").value = "";
  $("search").value = "";
  range = null;
  update();
});
$("useRange").addEventListener("click", () => {
  const values = $("access")._fullLayout?.xaxis.range;
  if (values?.length === 2 && values.every(Number.isFinite)) {
    range = {
      field: $("axis").value === "order" ? "order" : "time",
      lo: Math.min(...values),
      hi: Math.max(...values),
    };
    update();
  }
});
$("clearRange").addEventListener("click", () => {
  range = null;
  update();
});
$("resetZoom").addEventListener("click", () =>
  Plotly.relayout("access", {
    "xaxis.autorange": true,
    "yaxis.autorange": true,
  }),
);
window.addEventListener("resize", () => {
  drawStacks();
});
let resizeFrame;
new ResizeObserver(() => {
  cancelAnimationFrame(resizeFrame);
  resizeFrame = requestAnimationFrame(() => {
    drawStacks();
    if (activeTab === "pages") drawAccess();
  });
}).observe(document.querySelector(".plot-scroll"));
new ResizeObserver(() => {
  if (activeTab === "stacks" || activeTab === "flame") drawStacks();
}).observe($("stackScroll"));
new ResizeObserver(() => {
  if (!$("detail").hidden) syncDetailHeight();
}).observe(document.querySelector(".analysis-panel"));
changeRun();
