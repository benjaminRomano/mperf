// Pass this function to playwright-cli run-code with a generated report open.
async (page) => {
  const require = (value, message) => {
    if (!value) throw Error(message);
  };
  await page.locator("#run").selectOption("0");
  await page.locator("#reset").click();
  await page.locator("#tab-pages").click();
  for (const mode of ["offset", "delta-file"])
    require(await page
      .locator(`#view option[value="${mode}"]`)
      .evaluate(
        (el) => el.disabled,
      ), "All-source view exposed unrelated file coordinates");
  const fileSource = await page.evaluate(
    () => REPORT.runs[0].events.find((e) => e.page !== null)?.source,
  );
  if (fileSource) {
    await page.locator("#kind").selectOption("all");
    await page
      .locator("#sources button")
      .evaluateAll(
        (buttons, source) =>
          buttons.find((b) => b.dataset.source === source).click(),
        fileSource,
      );
    for (const mode of ["offset", "delta-file"])
      require(!(await page
        .locator(`#view option[value="${mode}"]`)
        .evaluate(
          (el) => el.disabled,
        )), "Selected file did not enable file coordinates");
    await page.locator("#view").selectOption("delta-file");
    await page.locator("#allSources").click();
    require((await page.locator("#view").inputValue()) ===
      "lanes", "Returning to all sources retained misleading file coordinates");
    await page.locator("#reset").click();
  }
  await page.locator("#view").selectOption("address");
  await page.locator("#axis").selectOption("order");
  const capture = await page.evaluate(() => {
    const run = REPORT.runs[0];
    const visible = run.events.filter(
      (e) => !run.fileBackedOnly || e.fileBacked,
    );
    return {
      total: REPORT.runs[0].events.length,
      plotted: visible.filter((e) => e.addressPlot !== false).length,
      plottedMajors: visible.filter((e) => e.major && e.addressPlot !== false)
        .length,
      addressDeltas: visible.filter(
        (e, i, all) =>
          i > 0 && e.addressPlot !== false && all[i - 1].addressPlot !== false,
      ).length,
      majors: visible.filter((e) => e.major).length,
      firstMajor: visible.find((e) => e.major)?.order,
      firstHalf: visible.filter(
        (e) => e.order <= Math.floor(run.events.length / 2),
      ).length,
    };
  });
  const majorIndices = await page
    .locator("#access")
    .evaluate((el) => el.data.find((t) => t.name === "Major").x);
  require(majorIndices.length ===
    capture.plottedMajors, "Major plot count differs from capture");
  await page.locator("#kind").selectOption("all");
  const all = await page.locator("#access").evaluate((el) => ({
    n: el.data.reduce((n, t) => n + t.x.length, 0),
    major: el.data.find((t) => t.name === "Major").x,
  }));
  require(all.n === capture.plotted, "Overview omitted plottable events");
  require(JSON.stringify(all.major) ===
    JSON.stringify(majorIndices), "Minor toggle renumbered major faults");
  if (capture.total > 1) {
    const end = Math.floor(capture.total / 2);
    await page
      .locator("#access")
      .evaluate(
        (el, end) => Plotly.relayout(el, { "xaxis.range": [1, end] }),
        end,
      );
    await page.locator("#useRange").click();
    require((await page.locator("#selectionCount").textContent()).startsWith(
      capture.firstHalf.toLocaleString() + " matching faults",
    ), "Plot zoom range did not filter original event indices");
    await page.locator("#clearRange").click();
  }
  await page.locator("#view").selectOption("delta-address");
  const deltas = await page
    .locator("#access")
    .evaluate((el) => el.data.reduce((n, t) => n + t.x.length, 0));
  require(deltas ===
    capture.addressDeltas, "Address deltas must not bridge unplottable events");
  await page.locator("#kind").selectOption("major");
  await page.locator("#tab-stacks").click();
  require((await page
    .locator("#stackLimit, #stackStart, #stackPrev, #stackNext, #stackReset")
    .count()) === 0, "Obsolete stack paging/zoom controls remain");
  require((await page.locator("#stackCount").textContent()).includes(
    "fully zoomed out",
  ), "Stack chart does not default to all faults");
  if (capture.majors) {
    await page.locator("#stackScroll").scrollIntoViewIfNeeded();
    await page.locator("#stackScroll").evaluate((el) => {
      el.scrollTop = 0;
    });
    const box = await page.locator("#stacks").boundingBox();
    await page.mouse.click(box.x + 1, box.y + 5);
    require((await page.locator("#detail").textContent()).includes(
      "#" + capture.firstMajor + " ·",
    ), "Stack click selected wrong event");
  }
  const viewportState = await page
    .locator("#stacks")
    .evaluate((el) => ({
      left: +el.dataset.viewportLeft,
      span: +el.dataset.viewportSpan,
    }));
  require(viewportState.span === 1 &&
    viewportState.left === 0, "Default stack viewport was not complete");
  const beforeDetail = await page.locator("#detail").textContent();
  await page.locator("#stackScroll").focus();
  await page.keyboard.press("Equal");
  const zoomed = await page
    .locator("#stacks")
    .evaluate((el) => +el.dataset.viewportSpan);
  require(zoomed < 1 || capture.majors <= 1, "Zoom did not enlarge stacks");
  await page.keyboard.press("d");
  require((await page.locator("#detail").textContent()) ===
    beforeDetail, "Panning changed the selected fault");
  await page.keyboard.press("Escape");
  require((await page
    .locator("#stacks")
    .evaluate((el) => +el.dataset.viewportSpan)) ===
    1, "Escape did not reset viewport");
  await page.locator("#tab-flame").click();
  require((await page.locator("#stacks").getAttribute("aria-label")).includes(
    capture.majors + " matching faults",
  ), "Flame denominator differs from filter");
  await page.locator("#tab-sites").click();
  require((await page.locator("#sites [data-fault]").count()) ===
    capture.majors, "Fault list aggregated or omitted events");
  if (capture.majors) {
    await page.locator("#sites [data-fault]").first().click();
    require((await page.locator("#detail").textContent()).includes(
      "#" + capture.firstMajor + " ·",
    ), "Fault list selected the wrong event");
  }
  await page.locator("#tab-flame").click();
  await page.locator("#search").fill("__no_such_fault_for_ui_qa__");
  require((await page.locator("#selectionCount").textContent()).startsWith(
    "0 matching faults",
  ), "Empty search retained events");
  require((await page.locator("#stacks").getAttribute("aria-label")).includes(
    "0 matching faults",
  ), "Empty search retained stacks");
  await page.locator("#reset").click();
  await page.locator("#tab-pages").click();
  await page.locator("#view").selectOption("address");
  await page.locator("#axis").selectOption("time");
  const dock = await page
    .locator("#toggleDetail")
    .getAttribute("aria-expanded");
  if (dock === "true") await page.locator("#toggleDetail").click();
  const desktop = await page.evaluate(() => ({
    height: innerHeight,
    scrollHeight: document.documentElement.scrollHeight,
  }));
  require(desktop.scrollHeight <=
    desktop.height, "Desktop report requires outer vertical scrolling");
  const viewport = page.viewportSize();
  await page.setViewportSize({ width: 390, height: 844 });
  const mobile = await page.evaluate(() => ({
    height: innerHeight,
    scrollHeight: document.documentElement.scrollHeight,
    width: innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  require(mobile.scrollWidth <=
    mobile.width, "Narrow-screen horizontal overflow");
  require(mobile.scrollHeight <=
    mobile.height, "Mobile report requires outer vertical scrolling");
  if (viewport) await page.setViewportSize(viewport);
  return {
    capture,
    deltaPoints: deltas,
    mobile,
    checks:
      "single-file coordinates, counts, stable indices, shared range, deltas, exact selection, flame weights, empty filters, narrow layout",
  };
}
