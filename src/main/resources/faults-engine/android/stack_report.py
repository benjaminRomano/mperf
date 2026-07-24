#!/usr/bin/env python3
"""Build an interactive page-fault stack report from Simpleperf samples."""

import argparse
import html
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

import plotly.graph_objects as go
import plotly.io as pio


HEADER_PATTERN = re.compile(
    r"^(?P<thread>.*)\t(?P<pid>\d+)/(?P<tid>\d+) "
    r"\[(?P<cpu>\d+)\] (?P<time>\d+\.\d+): "
    r"(?P<period>\d+) (?P<event>minor|major)-faults:$"
)
FRAME_PATTERN = re.compile(r"^\s*(?P<ip>[0-9a-f]+) (?P<symbol>.+) \((?P<dso>.+)\)$")

COLORS = {
    "app": "#2563EB",
    "framework": "#D97706",
    "art": "#DB2777",
    "native": "#6B8E23",
    "kernel": "#64748B",
    "unknown": "#CBD5E1",
}
CATEGORY_ORDER = tuple(COLORS)
RECORDING_SUMMARY_PATTERN = re.compile(
    r"Samples recorded:\s*(?P<recorded>[\d,]+)\.\s*"
    r"Samples lost:\s*(?P<lost>[\d,]+)\."
)


@dataclass(frozen=True)
class Frame:
    ip: int
    symbol: str
    dso: str


@dataclass(frozen=True)
class FaultStack:
    thread: str
    pid: int
    tid: int
    cpu: int
    timestamp_s: float
    period: int
    event_type: str
    frames: tuple[Frame, ...]  # Leaf first, then callers.


def parse_simpleperf_samples(path: Path) -> list[FaultStack]:
    samples: list[FaultStack] = []
    header: re.Match[str] | None = None
    frames: list[Frame] = []

    def finish_sample() -> None:
        nonlocal header, frames
        if header is None:
            return
        samples.append(
            FaultStack(
                thread=header.group("thread"),
                pid=int(header.group("pid")),
                tid=int(header.group("tid")),
                cpu=int(header.group("cpu")),
                timestamp_s=float(header.group("time")),
                period=int(header.group("period")),
                event_type=header.group("event"),
                frames=tuple(frames),
            )
        )
        header = None
        frames = []

    for line_number, raw_line in enumerate(
        path.read_text(errors="replace").splitlines(), start=1
    ):
        header_match = HEADER_PATTERN.match(raw_line)
        if header_match:
            finish_sample()
            header = header_match
            continue
        if not raw_line.strip():
            finish_sample()
            continue
        if header is None:
            raise ValueError(f"Unexpected line {line_number}: {raw_line!r}")
        frame_match = FRAME_PATTERN.match(raw_line)
        if not frame_match:
            raise ValueError(f"Invalid frame on line {line_number}: {raw_line!r}")
        frames.append(
            Frame(
                ip=int(frame_match.group("ip"), 16),
                symbol=frame_match.group("symbol"),
                dso=frame_match.group("dso"),
            )
        )
    finish_sample()
    return samples


def parse_recording_summary(path: Path) -> tuple[int, int]:
    match = RECORDING_SUMMARY_PATTERN.search(path.read_text(errors="replace"))
    if match is None:
        raise ValueError(f"No Simpleperf recording summary found in {path}")
    return (
        int(match.group("recorded").replace(",", "")),
        int(match.group("lost").replace(",", "")),
    )


def frame_category(frame: Frame) -> str:
    text = f"{frame.symbol} {frame.dso}"
    if "/data/app/" in frame.dso:
        return "app"
    if "libart.so" in frame.dso or "art::" in frame.symbol:
        return "art"
    if frame.dso == "[kernel.kallsyms]":
        return "kernel"
    if frame.dso.endswith((".oat", ".vdex", ".jar", ".art")):
        return "framework"
    if frame.dso.startswith(("/system/", "/apex/", "/vendor/", "/product/")):
        return "native"
    if frame.dso == "unknown" or " unknown" in text:
        return "unknown"
    return "native"


def compact_frame_label(frame: Frame) -> str:
    symbol = frame.symbol.replace(" [DEDUPED]", "")
    if len(symbol) <= 96:
        return symbol
    return symbol[:93] + "…"


def stack_tree(
    samples: list[FaultStack],
    title: str,
) -> dict[str, list[object]]:
    counts: Counter[tuple[tuple[int, str, str], ...]] = Counter()
    frame_by_path: dict[tuple[tuple[int, str, str], ...], Frame] = {}

    for sample in samples:
        caller_first: list[Frame] = []
        for frame in reversed(sample.frames):
            if not caller_first or frame != caller_first[-1]:
                caller_first.append(frame)
        path: tuple[tuple[int, str, str], ...] = ()
        for frame in caller_first:
            path += ((frame.ip, frame.symbol, frame.dso),)
            counts[path] += sample.period
            frame_by_path[path] = frame

    ordered_paths = sorted(counts, key=lambda path: (len(path), path))
    id_by_path = {path: f"n{index}" for index, path in enumerate(ordered_paths)}
    ids = ["root"]
    labels = [title]
    parents = [""]
    fault_count = sum(sample.period for sample in samples)
    values = [fault_count]
    colors = ["#172033"]
    customdata: list[object] = [["", "", fault_count]]

    for path in ordered_paths:
        frame = frame_by_path[path]
        ids.append(id_by_path[path])
        labels.append(compact_frame_label(frame))
        parents.append("root" if len(path) == 1 else id_by_path[path[:-1]])
        values.append(counts[path])
        colors.append(COLORS[frame_category(frame)])
        customdata.append([frame.dso, f"0x{frame.ip:x}", counts[path]])

    return {
        "ids": ids,
        "labels": labels,
        "parents": parents,
        "values": values,
        "colors": colors,
        "customdata": customdata,
    }


def stack_figure(samples: list[FaultStack]) -> go.Figure:
    scopes = [
        ("All", samples),
        ("Minor", [sample for sample in samples if sample.event_type == "minor"]),
        ("Major", [sample for sample in samples if sample.event_type == "major"]),
    ]
    figure = go.Figure()
    for index, (label, scoped_samples) in enumerate(scopes):
        tree = stack_tree(scoped_samples, f"{label} faults")
        trace_data = {key: value for key, value in tree.items() if key != "colors"}
        figure.add_trace(
            go.Icicle(
                **trace_data,
                name=label,
                branchvalues="total",
                sort=False,
                visible=index == 0,
                marker=dict(colors=tree["colors"], line=dict(width=0.35)),
                hovertemplate=(
                    "<b>%{label}</b><br>%{customdata[0]}<br>"
                    "%{customdata[1]}<br>%{value:,} faults"
                    "<extra></extra>"
                ),
                textinfo="label",
                maxdepth=28,
                tiling=dict(orientation="v", pad=1),
            )
        )
    figure.update_layout(
        margin=dict(l=8, r=8, t=8, b=8),
        height=760,
        paper_bgcolor="white",
        font=dict(family="Inter, system-ui, sans-serif", color="#172033"),
        meta={"scope_labels": [label for label, _ in scopes]},
    )
    return figure


def discrete_colorscale() -> list[list[object]]:
    scale: list[list[object]] = []
    last_index = len(CATEGORY_ORDER) - 1
    for index, category in enumerate(CATEGORY_ORDER):
        start = max(0.0, (index - 0.5) / last_index)
        end = min(1.0, (index + 0.5) / last_index)
        scale.extend([[start, COLORS[category]], [end, COLORS[category]]])
    return scale


def temporal_stack_figure(
    samples: list[FaultStack], max_frames: int = 64
) -> tuple[go.Figure, list[dict[str, object]], list[list[dict[str, object]]]]:
    """Build a sampled stack chart preserving fault time and stack depth."""
    if max_frames < 1:
        raise ValueError("max_frames must be positive")
    first_timestamp = min(sample.timestamp_s for sample in samples)
    frame_ids: dict[Frame, int] = {}
    frame_metadata: list[dict[str, object]] = []

    def frame_id(frame: Frame) -> int:
        identifier = frame_ids.get(frame)
        if identifier is not None:
            return identifier
        identifier = len(frame_metadata)
        frame_ids[frame] = identifier
        frame_metadata.append(
            {
                "symbol": frame.symbol.replace(" [DEDUPED]", ""),
                "dso": frame.dso,
                "ip": f"0x{frame.ip:x}",
                "category": frame_category(frame),
            }
        )
        return identifier

    scopes = [
        ("All", samples),
        ("Minor", [sample for sample in samples if sample.event_type == "minor"]),
        ("Major", [sample for sample in samples if sample.event_type == "major"]),
    ]
    figure = go.Figure()
    metadata_by_trace: list[list[dict[str, object]]] = []
    category_code = {name: index for index, name in enumerate(CATEGORY_ORDER)}

    for trace_index, (label, scoped_samples) in enumerate(scopes):
        ordered = sorted(
            scoped_samples, key=lambda sample: (sample.timestamp_s, sample.tid)
        )
        x = [(sample.timestamp_s - first_timestamp) * 1000 for sample in ordered]
        z: list[list[int | None]] = [[None for _ in ordered] for _ in range(max_frames)]
        customdata: list[list[int | None]] = [
            [None for _ in ordered] for _ in range(max_frames)
        ]
        trace_metadata: list[dict[str, object]] = []
        for column, sample in enumerate(ordered):
            caller_first: list[Frame] = []
            for frame in reversed(sample.frames[:max_frames]):
                if not caller_first or frame != caller_first[-1]:
                    caller_first.append(frame)
            for depth, frame in enumerate(caller_first):
                z[depth][column] = category_code[frame_category(frame)]
                customdata[depth][column] = frame_id(frame)
            trace_metadata.append(
                {
                    "thread": sample.thread,
                    "tid": sample.tid,
                    "cpu": sample.cpu,
                    "event": sample.event_type,
                    "time_ms": x[column],
                    "period": sample.period,
                    "captured_frames": len(sample.frames),
                    "shown_frames": len(caller_first),
                }
            )
        metadata_by_trace.append(trace_metadata)
        figure.add_trace(
            go.Heatmap(
                x=x,
                y=list(range(max_frames)),
                z=z,
                customdata=customdata,
                name=label,
                visible=trace_index == 0,
                zmin=0,
                zmax=len(CATEGORY_ORDER) - 1,
                colorscale=discrete_colorscale(),
                showscale=trace_index == 0,
                colorbar=dict(
                    title="Frame",
                    tickmode="array",
                    tickvals=list(range(len(CATEGORY_ORDER))),
                    ticktext=[name.title() for name in CATEGORY_ORDER],
                    thickness=14,
                ),
                hovertemplate=(
                    "Time %{x:.3f} ms<br>Stack depth %{y}<br>"
                    "Move over a frame for details below<extra></extra>"
                ),
                connectgaps=False,
                xgap=0,
                ygap=0,
            )
        )
    figure.update_layout(
        margin=dict(l=70, r=120, t=20, b=65),
        height=700,
        paper_bgcolor="white",
        plot_bgcolor="white",
        font=dict(family="Inter, system-ui, sans-serif", color="#172033"),
        hovermode="closest",
        xaxis=dict(
            title="Time since first captured fault (ms)",
            rangeslider=dict(visible=True, thickness=0.08),
        ),
        yaxis=dict(
            title="Captured stack depth (outer → faulting frame)",
            rangemode="tozero",
        ),
        meta={"scope_labels": [label for label, _ in scopes]},
    )
    return figure, frame_metadata, metadata_by_trace


def hotspot_rows(samples: list[FaultStack], limit: int = 15) -> list[dict[str, object]]:
    counts: dict[Frame, dict[str, int]] = defaultdict(lambda: {"minor": 0, "major": 0})
    for sample in samples:
        if sample.frames:
            counts[sample.frames[0]][sample.event_type] += sample.period
    ranked = sorted(
        counts.items(),
        key=lambda item: (
            item[1]["major"] + item[1]["minor"],
            item[1]["major"],
        ),
        reverse=True,
    )
    return [
        {
            "frame": compact_frame_label(frame),
            "dso": frame.dso,
            "minor": values["minor"],
            "major": values["major"],
            "total": values["minor"] + values["major"],
        }
        for frame, values in ranked[:limit]
    ]


def selector_control(chart_id: str, labels: list[str]) -> str:
    options = "".join(
        f'<option value="{index}">{html.escape(label)}</option>'
        for index, label in enumerate(labels)
    )
    return f"""
    <div class="control">
      <label for="{chart_id}-scope">Fault type</label>
      <select id="{chart_id}-scope">{options}</select>
    </div>
    <script>
    (() => {{
      const bind = () => {{
        const select = document.getElementById("{chart_id}-scope");
        const chart = document.getElementById("{chart_id}");
        select.addEventListener("change", async () => {{
          const selected = Number(select.value);
          await Plotly.restyle(chart, {{visible: false}});
          await Plotly.restyle(chart, {{visible: true}}, [selected]);
          if (chart.data[selected].type === "heatmap") {{
            await Plotly.restyle(chart, {{showscale: false}});
            await Plotly.restyle(chart, {{showscale: true}}, [selected]);
          }}
        }});
      }};
      if (document.readyState === "loading") {{
        document.addEventListener("DOMContentLoaded", bind, {{once: true}});
      }} else {{
        bind();
      }}
    }})();
    </script>
    """


def temporal_hover_script(
    chart_id: str,
    frame_metadata: list[dict[str, object]],
    metadata_by_trace: list[list[dict[str, object]]],
) -> str:
    frame_json = json.dumps(frame_metadata).replace("</", "<\\/")
    sample_json = json.dumps(metadata_by_trace).replace("</", "<\\/")
    return f"""
    <script>
    (() => {{
      const bind = () => {{
        const chart = document.getElementById("{chart_id}");
        const detail = document.getElementById("{chart_id}-detail");
        const frames = {frame_json};
        const samples = {sample_json};
        chart.on("plotly_hover", event => {{
          const point = event.points[0];
          const frame = frames[point.customdata];
          const column = Array.isArray(point.pointNumber)
            ? point.pointNumber[1] : point.pointNumber;
          const sample = samples[point.curveNumber][column];
          if (!frame || !sample) return;
          detail.textContent =
            `${{frame.symbol}} — ${{frame.dso}} (${{frame.ip}}) · ` +
            `${{sample.event}} fault · ${{sample.time_ms.toFixed(3)}} ms · ` +
            `${{sample.thread}} (tid ${{sample.tid}}, CPU ${{sample.cpu}}) · ` +
            `sample period ${{sample.period}}`;
        }});
      }};
      if (document.readyState === "loading") {{
        document.addEventListener("DOMContentLoaded", bind, {{once: true}});
      }} else {{
        bind();
      }}
    }})();
    </script>
    """


def integrity_status(
    samples: list[FaultStack],
    samples_recorded: int | None,
    samples_lost: int | None,
) -> tuple[str, str]:
    if samples_recorded is None or samples_lost is None:
        return (
            "unknown",
            "Recording integrity unknown: supply the Simpleperf recording log "
            "so the report can verify recorded and lost sample counts.",
        )
    parsed_count = len(samples)
    if samples_lost:
        return (
            "warning",
            f"Lossy capture: {samples_lost:,} samples were lost; "
            f"{samples_recorded:,} were recorded.",
        )
    if samples_recorded != parsed_count:
        return (
            "warning",
            f"Count mismatch: Simpleperf recorded {samples_recorded:,} samples "
            f"but the symbolized input contains {parsed_count:,}.",
        )
    return (
        "verified",
        f"Capture integrity verified: {samples_recorded:,} recorded, 0 lost.",
    )


def build_report(
    samples: list[FaultStack],
    source: Path,
    output: Path,
    samples_recorded: int | None = None,
    samples_lost: int | None = None,
) -> None:
    if not samples:
        raise ValueError("No page-fault stack samples were found")
    temporal_figure, frame_metadata, metadata_by_trace = temporal_stack_figure(samples)
    temporal_chart_id = "page-fault-stack-time"
    temporal_chart = pio.to_html(
        temporal_figure,
        full_html=False,
        include_plotlyjs=True,
        div_id=temporal_chart_id,
        config={"displaylogo": False, "responsive": True, "scrollZoom": True},
    )
    aggregate_figure = stack_figure(samples)
    aggregate_chart_id = "page-fault-stacks"
    aggregate_chart = pio.to_html(
        aggregate_figure,
        full_html=False,
        include_plotlyjs=False,
        div_id=aggregate_chart_id,
        config={"displaylogo": False, "responsive": True},
    )
    rows = "".join(
        "<tr>"
        f"<td>{html.escape(str(row['frame']))}"
        f"<small>{html.escape(str(row['dso']))}</small></td>"
        f"<td>{row['total']:,}</td><td>{row['major']:,}</td>"
        f"<td>{row['minor']:,}</td></tr>"
        for row in hotspot_rows(samples)
    )
    event_counts: Counter[str] = Counter()
    for sample in samples:
        event_counts[sample.event_type] += sample.period
    depths = sorted(len(sample.frames) for sample in samples)
    median_depth = depths[len(depths) // 2]
    thread_count = len({sample.tid for sample in samples})
    temporal_control = selector_control(
        temporal_chart_id,
        [str(label) for label in temporal_figure.layout.meta["scope_labels"]],
    )
    aggregate_control = selector_control(
        aggregate_chart_id,
        [str(label) for label in aggregate_figure.layout.meta["scope_labels"]],
    )
    integrity_class, integrity_text = integrity_status(
        samples, samples_recorded, samples_lost
    )
    hover_script = temporal_hover_script(
        temporal_chart_id, frame_metadata, metadata_by_trace
    )
    document = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Android page-fault stack report</title>
  <style>
    :root {{ color-scheme: light; font-family: Inter, system-ui, sans-serif; }}
    body {{ margin: 0; background: #f5f7fb; color: #172033; }}
    main {{ max-width: 1500px; margin: auto; padding: 32px; }}
    section {{ background: white; border: 1px solid #dce3ec; border-radius: 18px;
      padding: 28px; margin-bottom: 24px; }}
    h1, h2 {{ margin-top: 0; }}
    p {{ line-height: 1.5; }}
    .metrics {{ display: flex; gap: 12px; flex-wrap: wrap; }}
    .metric {{ background: #eef3fa; border-radius: 12px; padding: 12px 16px; }}
    .metric strong {{ display: block; font-size: 1.4rem; }}
    .control {{ display: grid; gap: 6px; max-width: 420px; margin: 16px 0; }}
    label, th {{ color: #5b687c; font-size: .8rem; font-weight: 700;
      text-transform: uppercase; letter-spacing: .04em; }}
    select {{ font: inherit; padding: 10px 12px; border: 1px solid #b8c4d5;
      border-radius: 8px; background: white; }}
    table {{ width: 100%; border-collapse: collapse; }}
    th, td {{ padding: 10px; border-bottom: 1px solid #e4e9f0; text-align: right; }}
    th:first-child, td:first-child {{ text-align: left; }}
    td small {{ display: block; color: #64748b; margin-top: 3px; }}
    .note {{ color: #5b687c; }}
    .warning {{ border-left: 5px solid #d97706; padding: 12px 16px;
      background: #fff7ed; }}
    .verified {{ border-left-color: #15803d; background: #f0fdf4; }}
    .detail {{ min-height: 24px; padding: 10px 12px; background: #f3f6fa;
      border-radius: 8px; overflow-wrap: anywhere; }}
    .legend {{ display: flex; flex-wrap: wrap; gap: 12px; color: #5b687c; }}
    .swatch {{ display: inline-block; width: 10px; height: 10px;
      border-radius: 2px; margin-right: 5px; }}
    @media (max-width: 700px) {{
      main {{ padding: 12px; }}
      section {{ padding: 16px; }}
      table {{ font-size: .82rem; }}
    }}
  </style>
</head>
<body>
<main>
  <section>
    <h1>Android page-fault call stacks</h1>
    <p>See when each sampled page fault happened and which call stack led to it.
    Use the time range slider or drag to zoom; hover a frame for its symbol,
    library, thread, and fault type.</p>
    <div class="metrics">
      <div class="metric"><strong>{len(samples):,}</strong>fault samples</div>
      <div class="metric"><strong>{event_counts['major']:,}</strong>major</div>
      <div class="metric"><strong>{event_counts['minor']:,}</strong>minor</div>
      <div class="metric"><strong>{thread_count:,}</strong>threads</div>
      <div class="metric"><strong>{median_depth:,}</strong>median frames</div>
    </div>
  </section>
  <section>
    <h2>Stack chart over time</h2>
    {temporal_control}
    {temporal_chart}
    <div id="{temporal_chart_id}-detail" class="detail">Hover a frame for details.</div>
    {hover_script}
    <div class="legend">
      {"".join(f'<span><i class="swatch" style="background:{COLORS[name]}"></i>{name.title()}</span>' for name in CATEGORY_ORDER)}
    </div>
    <p class="note">Each column is one sampled fault at its timestamp; column
    width reflects spacing between samples, not execution duration. Threads are
    interleaved. The outermost captured frame is at the bottom and the faulting
    instruction is at the top. At most 64 frames are shown per sample.</p>
  </section>
  <section>
    <h2>Aggregated call paths</h2>
    <p>Block width is the weighted fault count reaching a frame. Click a block
    to zoom into that path.</p>
    {aggregate_control}
    {aggregate_chart}
  </section>
  <section>
    <h2>Top faulting instructions</h2>
    <table><thead><tr><th>Leaf frame</th><th>Total</th><th>Major</th>
    <th>Minor</th></tr></thead><tbody>{rows}</tbody></table>
  </section>
  <section>
    <h2>Measurement note</h2>
    <p class="warning {integrity_class}"><strong>{html.escape(integrity_text)}</strong></p>
    <p class="warning"><strong>Cache state is unverified in the standalone
    Simpleperf workflow.</strong> Do not compare its major/minor mix with a
    strict cache-cold exact run unless the same eviction and residency gate was
    executed immediately before this launch.</p>
    <p>This is a stack-enabled Simpleperf capture, not the authoritative
    low-overhead fault-count run. DWARF unwinding provides useful Java, ART,
    framework, and native stacks, but it perturbs startup and may miss the
    earliest faults while attaching to the new process.</p>
    <p class="note">Parsed from {html.escape(str(source))}.</p>
  </section>
</main>
</body>
</html>"""
    output.write_text(document)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate an interactive page-fault stack report"
    )
    parser.add_argument("samples", type=Path, help="Simpleperf report_sample.py text")
    parser.add_argument("--output", type=Path, default=Path("stack-report.html"))
    parser.add_argument(
        "--recording-log",
        type=Path,
        help="Simpleperf record output containing recorded/lost sample counts",
    )
    parser.add_argument(
        "--allow-loss",
        action="store_true",
        help="Generate a visibly warned report even when Simpleperf lost samples",
    )
    args = parser.parse_args()
    samples = parse_simpleperf_samples(args.samples)
    recorded: int | None = None
    lost: int | None = None
    if args.recording_log is not None:
        recorded, lost = parse_recording_summary(args.recording_log)
        if lost and not args.allow_loss:
            raise RuntimeError(
                f"Simpleperf lost {lost:,} samples; rerun or pass --allow-loss"
            )
    build_report(samples, args.samples, args.output, recorded, lost)
    print(f"Report written: {args.output.resolve()}")


if __name__ == "__main__":
    main()
