/* https://perfetto.dev/docs/visualization/deep-linking-to-perfetto-ui */
var FaultPerfetto = (() => {
  const origin = "https://ui.perfetto.dev";
  function create({ button, download, status, host = window }) {
    let run, cancel, generation = 0;
    function message(text) {
      status.textContent = text;
      status.hidden = !text;
    }
    function setRun(next) {
      generation++;
      cancel?.();
      run = next;
      button.disabled = !run.perfettoTrace;
      button.title = run.perfettoTrace
        ? "Open this run's startup trace in Perfetto; trace data stays in your browser"
        : "No associated Perfetto trace is available for this run";
      download.hidden = !run.perfettoTrace;
      if (run.perfettoTrace) download.href = run.perfettoTrace;
      else download.removeAttribute("href");
      message("");
    }
    async function open() {
      if (!run?.perfettoTrace || button.disabled) return;
      const current = generation;
      const trace = new URL(run.perfettoTrace, host.location.href);
      if (trace.protocol === "file:") {
        host.open(origin, "_blank");
        message("Open faults.pftrace from this capture in Perfetto. For one-click loading, serve the report directory over HTTP.");
        return;
      }
      if (trace.origin !== host.location.origin || !["http:", "https:"].includes(trace.protocol)) {
        message("The associated trace must be served alongside this report.");
        return;
      }
      // Open during the click, before fetching, to preserve the browser's user gesture.
      const popup = host.open(origin, "_blank");
      if (!popup) {
        message("Allow popups for this report, then try again. Or download Trace file and open it in Perfetto.");
        return;
      }
      button.disabled = true;
      message("Opening startup trace in Perfetto…");
      const controller = new AbortController();
      let interval, timeout, listener;
      const cleanup = () => {
        host.clearInterval(interval);
        host.clearTimeout(timeout);
        host.removeEventListener("message", listener);
        controller.abort();
      };
      const ready = new Promise((resolve, reject) => {
        cancel = () => {
          cleanup();
          reject(new Error("Opening cancelled"));
        };
        listener = (event) => {
          if (event.source === popup && event.origin === origin && event.data === "PONG") resolve();
        };
        host.addEventListener("message", listener);
        interval = host.setInterval(() => {
          if (popup.closed) {
            cleanup();
            reject(new Error("Perfetto window was closed"));
          } else popup.postMessage("PING", origin);
        }, 200);
        timeout = host.setTimeout(() => {
          cleanup();
          reject(new Error("Perfetto did not become ready within 60 seconds"));
        }, 60000);
      });
      try {
        const [buffer] = await Promise.all([
          host.fetch(trace.href, { signal: controller.signal }).then(async (response) => {
            if (!response.ok) throw new Error("Trace fetch failed (HTTP " + response.status + ")");
            const bytes = await response.arrayBuffer();
            if (!bytes.byteLength) throw new Error("Trace file is empty");
            return bytes;
          }),
          ready,
        ]);
        if (current !== generation) return;
        popup.postMessage(
          { perfetto: { buffer, title: run.label + " · startup", fileName: "faults.pftrace" } },
          origin,
          [buffer],
        );
        message("Trace sent to Perfetto in your browser; no upload.");
      } catch (error) {
        if (current === generation)
          message(error.message + ". Download Trace file and open it in Perfetto, or serve the complete capture directory.");
      } finally {
        cleanup();
        if (current === generation) {
          cancel = null;
          button.disabled = !run.perfettoTrace;
        }
      }
    }
    button.addEventListener("click", open);
    host.addEventListener("pagehide", () => cancel?.());
    return { setRun, open };
  }
  return { create };
})();
