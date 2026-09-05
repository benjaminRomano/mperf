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
  return { page, deltas, median, regions, matchesRegion, detailSize };
})();
