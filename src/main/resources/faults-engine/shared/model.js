/* Pure page-coordinate helpers. Keep BigInt until after page alignment. */
globalThis.FaultModel = (() => {
  function page(event, pageSize) {
    if (event.addressPlot === false) return null;
    return BigInt(event.address) / BigInt(pageSize);
  }
  function deltas(events, pageSize, space) {
    const result = [];
    for (let i = 1; i < events.length; i++) {
      const event = events[i],
        previous = events[i - 1];
      if (
        space === "file" &&
        (event.page === null ||
          previous.page === null ||
          event.source !== previous.source)
      )
        continue;
      if (
        space !== "file" &&
        (page(event, pageSize) === null || page(previous, pageSize) === null)
      )
        continue;
      const value =
        space === "file"
          ? event.page - previous.page
          : Number(page(event, pageSize) - page(previous, pageSize));
      result.push({ event, previous, value });
    }
    return result;
  }
  function median(values) {
    if (!values.length) return null;
    const sorted = values.slice().sort((a, b) => a - b);
    return (
      (sorted[Math.floor((sorted.length - 1) / 2)] +
        sorted[Math.floor(sorted.length / 2)]) /
      2
    );
  }
  function regions(event) {
    const detail = event.detail || {};
    return ["section", "dex"]
      .filter((key) => detail[key])
      .map((key) => ({
        value: key + ":" + detail[key],
        label:
          (key === "section"
            ? "Section"
            : /^classes\d*\.dex$/.test(detail[key])
              ? "DEX"
              : "Entry") +
          " · " +
          detail[key],
      }));
  }
  function matchesRegion(event, value) {
    return !value || regions(event).some((region) => region.value === value);
  }
  function detailSize(available, requested) {
    const space = Math.max(0, available);
    const max = Math.floor(space - Math.min(120, space * 0.4));
    const min = Math.min(90, max);
    return {
      min,
      max,
      height: Math.round(Math.max(min, Math.min(max, requested))),
    };
  }
  function sourceVisibility(events, fileBackedOnly) {
    const visible = [],
      hidden = [];
    for (const event of events)
      (fileBackedOnly && !event.fileBacked ? hidden : visible).push(event);
    return { visible, hidden };
  }
  function matchesCallerDex(event, value) {
    const dex = event.callerDex || [];
    if (!value) return true;
    if (value === "unknown") return !dex.length;
    if (value === "dex3plus") return dex.some((name) => /^classes(?:[3-9]|[1-9][0-9]+)\.dex$/.test(name));
    return dex.includes(value);
  }
  function experimentCohorts(runs) {
    const groups = new Map();
    for (const run of runs.filter((r) => r.experiment && !r.stacksOnly)) {
      const key = run.cohort || run.label;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(run.experiment);
    }
    return [...groups].map(([cohort, rows]) => ({cohort, count: rows.length,
      fullyDrawnCount: rows.filter((r) => Number.isFinite(r.fullyDrawnMs)).length,
      fullyDrawnMedian: median(rows.map((r) => r.fullyDrawnMs).filter(Number.isFinite)),
      appMajorMedian: median(rows.map((r) => r.appMajorFaults)),
      dex3PlusMedian: median(rows.map((r) => r.dex3Plus)),
    }));
  }
  return {
    matchesCallerDex,
    experimentCohorts,
    page,
    deltas,
    median,
    regions,
    matchesRegion,
    detailSize,
    sourceVisibility,
  };
})();
