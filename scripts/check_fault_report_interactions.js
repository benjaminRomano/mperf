// Run with playwright-cli run-code after opening a generated Android fault report.
async (page) => {
  const require = (ok, message) => {
    if (!ok) throw Error(message);
  };
  await page.locator("#run").selectOption("0");
  await page.locator("#reset").click();
  const totals = await page.evaluate(() => {
    const major = REPORT.runs[0].events.filter((e) => e.major);
    return {
      all: major.length,
      file: major.filter((e) => e.fileBacked).length,
    };
  });
  const sourceTotal = () =>
    page
      .locator("#sources tr")
      .evaluateAll((rows) =>
        rows.reduce(
          (sum, row) =>
            sum +
            Number((row.cells[1]?.textContent || "0").replaceAll(",", "")),
          0,
        ),
      );
  require((await sourceTotal()) ===
    totals.file, "Default source rows must sum to file-backed majors");
  require((await page.locator("#selectionCount").textContent()).includes(
    totals.file.toLocaleString() + " matching faults",
  ), "Visible count must match source totals");
  if (totals.all > totals.file)
    require((await page.locator("#selectionCount").textContent()).includes(
      (totals.all - totals.file).toLocaleString() +
        " anonymous / unknown hidden",
    ), "Excluded majors must be counted explicitly");
  await page.locator("#includeNonFile").check();
  require((await sourceTotal()) ===
    totals.all, "Including anonymous mappings must reconcile every captured major");
  await page.locator("#tab-sites").click();
  require((await page.locator("#sites [data-fault]").count()) ===
    totals.all, "All majors must be inspectable in the fault list");
  await page.locator("#reset").click();
  await page.locator("#tab-pages").click();
  await page.locator("#view").selectOption("address");
  for (const viewport of [
    { width: 1600, height: 1000 },
    { width: 1100, height: 800 },
  ]) {
    await page.setViewportSize(viewport);
    await page.waitForFunction(() => {
      const el = document.getElementById("access"),
        host = el.parentElement;
      return (
        Math.abs(el._fullLayout?.width - host.clientWidth) <= 1 &&
        Math.abs(el._fullLayout?.height - host.clientHeight) <= 1
      );
    });
    const bounds = await page.locator("#access").evaluate((el) => {
      const outer = el.getBoundingClientRect();
      return [...el.querySelectorAll(".ytitle, .xtitle, .ytick text")].map(
        (label) => {
          const rect = label.getBoundingClientRect();
          return {
            text: label.textContent,
            fits:
              rect.left >= outer.left - 1 &&
              rect.right <= outer.right + 1 &&
              rect.top >= outer.top - 1 &&
              rect.bottom <= outer.bottom + 1,
          };
        },
      );
    });
    require(bounds.length > 2 &&
      bounds.every((b) => b.fits), "Axis titles and address labels must fit: " +
      JSON.stringify(bounds));
  }
  await page.locator("#tab-stacks").click();
  await page.locator("#tab-pages").click();
  await page.waitForFunction(
    () =>
      Math.abs(
        document.getElementById("access")._fullLayout.height -
          document.getElementById("access").parentElement.clientHeight,
      ) <= 1,
  );
  await page.locator("#view").selectOption("lanes");
  await page.waitForFunction(
    () =>
      document.getElementById("access")._fullLayout.yaxis.type === "category",
  );
  require(await page
    .locator("#access")
    .evaluate((el) =>
      el.layout.yaxis.ticktext.every((text) => !text.includes("…")),
    ), "Lane labels must not discard their middle text");
  require(await page
    .locator("#sources button")
    .evaluateAll((buttons) =>
      buttons.every((b) => b.scrollWidth <= b.clientWidth + 1),
    ), "Source labels must wrap within the sidebar");
  await page.locator("#kind").selectOption("all");
  await page.locator("#tab-stacks").click();
  await page.locator("#stackScroll").evaluate((el) => {
    const rect = el.getBoundingClientRect();
    for (let i = 0; i < 4; i++)
      el.dispatchEvent(
        new WheelEvent("wheel", {
          deltaY: -100,
          ctrlKey: true,
          clientX: rect.left + rect.width / 2,
          bubbles: true,
          cancelable: true,
        }),
      );
  });
  await page.waitForFunction(
    () => Number(document.getElementById("stacks").dataset.viewportSpan) < 0.01,
  );
  await page.locator("#stackScroll").focus();
  await page.keyboard.press("Escape");
  await page.waitForFunction(
    () => document.getElementById("stacks").dataset.viewportSpan === "1",
  );
  await page.locator("#tab-pages").click();
  await page.locator("#view").selectOption("address");
  await page.screenshot({
    path: "output/playwright/fault-report-full-panel.png",
  });
  console.log(
    JSON.stringify({
      totals,
      result: "layout, labels, accounting and fast zoom passed",
    }),
  );
}
