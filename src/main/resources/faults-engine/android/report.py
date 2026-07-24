import argparse
import html
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import pandas as pd
import plotly.graph_objects as go
import plotly.io as pio
from plotly.subplots import make_subplots


MAJOR_COLOR = "#D97706"
MINOR_COLOR = "#2563EB"
BASE_COLOR = "#2563EB"
COMPARE_COLOR = "#D97706"
NEUTRAL_COLOR = "#64748B"
GRID_COLOR = "#E2E8F0"
INK_COLOR = "#172033"
COMPARISON_PROVENANCE_FIELDS = (
    "package",
    "activity",
    "serial",
    "device",
    "build_fingerprint",
    "sdk",
    "release",
    "abi",
    "page_size",
    "kernel",
    "collector",
    "collector_version",
    "collector_source_sha256",
    "collector_binary_sha256",
    "trace_config_sha256",
    "cache_procedure",
    "cache_max_resident_pages",
    "reboot_before_collect",
    "ndk",
    "compiler",
)


@dataclass
class Capture:
    path: Path
    label: str
    metadata: dict
    all_faults: pd.DataFrame
    mapped_faults: pd.DataFrame
    page_cache: pd.DataFrame
    residency: pd.DataFrame

    @property
    def page_size(self) -> int:
        return int(self.metadata["page_size"])

    @property
    def package(self) -> str:
        return str(self.metadata["package"])


@dataclass
class SequenceView:
    key: str
    label: str
    faults: pd.DataFrame


def read_capture(path: Path, label: Optional[str] = None) -> Capture:
    metadata_path = path / "capture_metadata.json"
    if not metadata_path.exists():
        raise RuntimeError(
            f"{path} is not an exact capture: capture_metadata.json is missing"
        )
    metadata = json.loads(metadata_path.read_text())
    if metadata.get("schema_version") != 5:
        raise RuntimeError(f"Unsupported capture schema in {path}")
    missing_provenance = [
        field
        for field in COMPARISON_PROVENANCE_FIELDS
        if metadata.get(field) in (None, "")
    ]
    if missing_provenance:
        raise RuntimeError(
            f"Capture {path} is missing required provenance: "
            + ", ".join(missing_provenance)
        )

    def read_csv(name: str) -> pd.DataFrame:
        csv_path = path / name
        return pd.read_csv(csv_path) if csv_path.exists() else pd.DataFrame()

    capture = Capture(
        path=path,
        label=label or path.name,
        metadata=metadata,
        all_faults=read_csv("all_faults.csv"),
        mapped_faults=read_csv("mapped_faults.csv"),
        page_cache=read_csv("page_cache_events.csv"),
        residency=read_csv("cache_residency.csv"),
    )
    for frame in (capture.all_faults, capture.mapped_faults):
        if not frame.empty:
            if frame["is_major"].dtype != bool:
                frame["is_major"] = frame["is_major"].map(
                    lambda value: str(value).lower() == "true"
                )
            frame["source_label"] = frame.apply(
                lambda row: source_label(row, capture.package), axis=1
            )
            frame["unit_key"] = frame.apply(
                lambda row: unit_key(row, capture.package), axis=1
            )
            frame["section_page"] = frame.apply(
                lambda row: section_page(row, capture.page_size), axis=1
            )
    if not capture.page_cache.empty:
        capture.page_cache["source_label"] = capture.page_cache.apply(
            lambda row: source_label(row, capture.package), axis=1
        )
    return capture


def app_relative_path(file_name: str, package: str) -> str:
    marker = f"/{package}-"
    if "/data/app/" in file_name and marker in file_name:
        suffix = file_name.split(marker, 1)[1]
        return suffix.split("/", 1)[1] if "/" in suffix else suffix
    return file_name


def source_label(row: pd.Series, package: str) -> str:
    file_name = str(row.get("file_name") or "Unattributed")
    relative = app_relative_path(file_name, package)
    if relative == file_name and not file_name.startswith("/data/app/"):
        relative = file_name
    zip_entry = row.get("zip_entry_name")
    if pd.notna(zip_entry) and zip_entry:
        return f"{relative} › {zip_entry}"
    return relative


def short_source_label(label: str) -> str:
    """Compact a source for axes while preserving the full path in hover."""
    if " › " in label:
        container, section = label.split(" › ", 1)
        return f"{Path(container).name} › {section}"
    path = Path(label)
    name = path.name or label
    parent = path.parent.name
    if name in {"base.art", "base.odex", "base.vdex"} and parent:
        return f"{parent}/{name}"
    return name


def unit_key(row: pd.Series, package: str) -> str:
    file_name = app_relative_path(str(row["file_name"]), package)
    zip_entry = row.get("zip_entry_name")
    return (
        f"{file_name}::{zip_entry}" if pd.notna(zip_entry) and zip_entry else file_name
    )


def section_page(row: pd.Series, page_size: int) -> float:
    zip_offset = row.get("zip_entry_offset")
    if pd.notna(zip_offset):
        return int(zip_offset) // page_size
    page_index = row.get("page_index")
    return int(page_index) if pd.notna(page_index) else math.nan


def compact_number(value: float) -> str:
    absolute = abs(value)
    if absolute >= 1_000_000:
        return f"{value / 1_000_000:.1f}M"
    if absolute >= 1_000:
        return f"{value / 1_000:.1f}k"
    return f"{value:,.0f}"


def percent(value: float) -> str:
    return "—" if math.isnan(value) else f"{value * 100:.1f}%"


def figure_layout(
    figure: go.Figure, height: int = 520, margin: Optional[dict] = None
) -> go.Figure:
    figure.update_layout(
        height=height,
        margin=margin or dict(l=64, r=28, t=80, b=64),
        paper_bgcolor="white",
        plot_bgcolor="white",
        font=dict(family="Inter, system-ui, sans-serif", color=INK_COLOR),
        hoverlabel=dict(font_size=13),
        legend=dict(
            orientation="h",
            yanchor="bottom",
            y=1.02,
            xanchor="left",
            x=0,
        ),
        modebar=dict(orientation="v"),
    )
    figure.update_xaxes(gridcolor=GRID_COLOR, zerolinecolor=GRID_COLOR)
    figure.update_yaxes(gridcolor=GRID_COLOR, zerolinecolor=GRID_COLOR)
    return figure


def source_summary(capture: Capture, limit: int = 15) -> pd.DataFrame:
    if capture.mapped_faults.empty:
        return pd.DataFrame(columns=["major", "minor", "total"])
    grouped = (
        capture.mapped_faults.groupby(["source_label", "event_type"])
        .size()
        .unstack(fill_value=0)
    )
    for column in ("major", "minor"):
        if column not in grouped:
            grouped[column] = 0
    grouped["total"] = grouped["major"] + grouped["minor"]
    return grouped.sort_values(["major", "total"], ascending=[False, False]).head(limit)


def top_sources_figure(capture: Capture) -> go.Figure:
    summary = source_summary(capture).sort_values("total")
    figure = go.Figure()
    figure.add_bar(
        y=summary.index,
        x=summary["minor"],
        orientation="h",
        name="Minor",
        marker=dict(color=MINOR_COLOR),
        customdata=summary[["total"]],
        hovertemplate=(
            "%{y}<br>Minor faults: %{x:,}<br>Total faults: %{customdata[0]:,}"
            "<extra></extra>"
        ),
    )
    figure.add_bar(
        y=summary.index,
        x=summary["major"],
        orientation="h",
        name="Major",
        marker=dict(color=MAJOR_COLOR),
        hovertemplate="%{y}<br>Major faults: %{x:,}<extra></extra>",
    )
    figure.update_layout(
        title="File-backed page faults by source",
        barmode="stack",
        xaxis_title="Fault count",
        yaxis_title="",
    )
    return figure_layout(figure, max(520, 28 * len(summary) + 180))


def category_figure(capture: Capture) -> go.Figure:
    if capture.mapped_faults.empty:
        figure = go.Figure()
        figure.update_layout(title="No file-backed faults were attributed")
        return figure_layout(figure, 430)
    grouped = (
        capture.mapped_faults.groupby(["category", "event_type"])
        .size()
        .unstack(fill_value=0)
    )
    for column in ("major", "minor"):
        if column not in grouped:
            grouped[column] = 0
    grouped["total"] = grouped["major"] + grouped["minor"]
    grouped = grouped.sort_values("total")
    figure = go.Figure()
    figure.add_bar(
        y=grouped.index,
        x=grouped["minor"],
        orientation="h",
        name="Minor",
        marker=dict(color=MINOR_COLOR),
    )
    figure.add_bar(
        y=grouped.index,
        x=grouped["major"],
        orientation="h",
        name="Major",
        marker=dict(color=MAJOR_COLOR),
    )
    figure.update_layout(
        title="File-backed faults by attributed section",
        barmode="stack",
        xaxis_title="Fault count",
        yaxis_title="",
    )
    return figure_layout(figure, max(430, 30 * len(grouped) + 170))


def all_fault_address_figure(capture: Capture) -> go.Figure:
    figure = go.Figure()
    required = {"address", "elapsed_ms", "event_type"}
    if capture.all_faults.empty or not required.issubset(capture.all_faults.columns):
        figure.update_layout(title="No startup page-fault addresses are available")
        return figure_layout(figure, 420)

    faults = capture.all_faults.copy()
    faults["address"] = pd.to_numeric(faults["address"], errors="coerce")
    faults = faults[faults["address"].notna()].copy()
    if faults.empty:
        figure.update_layout(title="No startup page-fault addresses are available")
        return figure_layout(figure, 420)

    faults["is_file_backed"] = (
        faults.get("mapping_kind", pd.Series(index=faults.index, dtype=object)).eq(
            "file"
        )
        & faults.get("file_name", pd.Series(index=faults.index, dtype=object)).notna()
    )
    faults["address_label"] = faults["address"].map(lambda value: f"0x{int(value):x}")
    faults["source_display"] = faults.get(
        "source_label", pd.Series("Unattributed", index=faults.index)
    ).fillna("Unattributed")
    missing_source = (
        faults["source_display"]
        .astype(str)
        .str.lower()
        .isin({"", "nan", "none", "unattributed"})
    )
    anonymous_mask = faults.get(
        "mapping_kind", pd.Series(index=faults.index, dtype=object)
    ).eq("anonymous")
    unmapped_mask = faults.get(
        "mapping_kind", pd.Series(index=faults.index, dtype=object)
    ).eq("unmapped")
    faults.loc[missing_source & anonymous_mask, "source_display"] = (
        "Unnamed anonymous / non-regular mapping"
    )
    faults.loc[missing_source & unmapped_mask, "source_display"] = "Unmapped address"

    def display_integer(column: str, suffix: str = "") -> pd.Series:
        values = faults.get(column, pd.Series(index=faults.index, dtype=object))
        return values.map(
            lambda value: ("—" if pd.isna(value) else f"{int(value):,}{suffix}")
        )

    faults["offset_display"] = display_integer("offset", " B")
    faults["page_display"] = display_integer("page_index")
    faults["thread_display"] = faults.get(
        "thread_name", pd.Series("—", index=faults.index)
    ).fillna("—")
    faults["category_display"] = faults.get(
        "category", pd.Series("—", index=faults.index)
    ).fillna("—")
    faults["mapping_display"] = faults.get(
        "mapping_kind", pd.Series("—", index=faults.index)
    ).fillna("—")
    faults["tid_display"] = display_integer("tid")
    faults["sequence_display"] = display_integer("sequence")

    trace_scopes = [
        ("all", pd.Series(True, index=faults.index), True),
        ("file", faults["is_file_backed"], False),
        ("anonymous", anonymous_mask, False),
        ("unmapped", unmapped_mask, False),
    ]
    trace_types = [
        ("minor", MINOR_COLOR, "circle"),
        ("major", MAJOR_COLOR, "diamond"),
    ]
    visibility_by_scope: dict[str, list[bool]] = {
        scope: [] for scope, _, _ in trace_scopes
    }
    for scope, scope_mask, initially_visible in trace_scopes:
        for event_type, color, symbol in trace_types:
            subset = faults[
                scope_mask & faults["event_type"].eq(event_type)
            ].sort_values("elapsed_ms")
            figure.add_trace(
                go.Scattergl(
                    x=subset["elapsed_ms"],
                    y=subset["address"],
                    mode="markers",
                    name=f"{event_type.title()} ({len(subset):,})",
                    legendgroup=f"{scope}-{event_type}",
                    showlegend=True,
                    visible=initially_visible,
                    marker=dict(
                        color=color,
                        size=8 if event_type == "major" else 5,
                        symbol=symbol,
                        opacity=0.9 if event_type == "major" else 0.42,
                        line=(
                            dict(color="#92400E", width=0.8)
                            if event_type == "major"
                            else dict(width=0)
                        ),
                    ),
                    customdata=subset[
                        [
                            "address_label",
                            "source_display",
                            "offset_display",
                            "page_display",
                            "thread_display",
                            "tid_display",
                            "category_display",
                            "mapping_display",
                            "sequence_display",
                        ]
                    ],
                    hovertemplate=(
                        "%{x:.3f} ms · %{customdata[0]}<br>"
                        "%{customdata[1]}<br>"
                        "File offset: %{customdata[2]} · "
                        "File page: %{customdata[3]}<br>"
                        "Thread: %{customdata[4]} (TID %{customdata[5]})<br>"
                        "Category: %{customdata[6]} · "
                        "Mapping: %{customdata[7]}<br>"
                        "Fault sequence: %{customdata[8]}<extra></extra>"
                    ),
                )
            )
            for button_scope in visibility_by_scope:
                visibility_by_scope[button_scope].append(scope == button_scope)

    minimum = int(faults["address"].min())
    maximum = int(faults["address"].max())
    tick_count = 7
    if minimum == maximum:
        tick_values = [minimum]
    else:
        tick_values = [
            round(minimum + index * (maximum - minimum) / (tick_count - 1))
            for index in range(tick_count)
        ]
    title = "Startup page faults by virtual address"
    figure.update_layout(
        title=title,
        xaxis_title="Elapsed startup time (ms)",
        yaxis_title="Virtual page-fault address",
        hovermode="closest",
        legend=dict(groupclick="togglegroup"),
        updatemenus=[
            {
                "buttons": [
                    {
                        "label": "All faults",
                        "method": "update",
                        "args": [
                            {"visible": visibility_by_scope["all"]},
                        ],
                    },
                    {
                        "label": "Regular-file backed",
                        "method": "update",
                        "args": [
                            {"visible": visibility_by_scope["file"]},
                        ],
                    },
                    {
                        "label": "Anonymous / non-regular",
                        "method": "update",
                        "args": [
                            {"visible": visibility_by_scope["anonymous"]},
                        ],
                    },
                    {
                        "label": "Unmapped only",
                        "method": "update",
                        "args": [
                            {"visible": visibility_by_scope["unmapped"]},
                        ],
                    },
                ],
                "direction": "down",
                "x": 1,
                "xanchor": "right",
                "y": 1,
                "yanchor": "top",
            }
        ],
        uirevision="all-fault-addresses",
    )
    figure.update_yaxes(
        tickmode="array",
        tickvals=tick_values,
        ticktext=[f"0x{value:x}" for value in tick_values],
    )
    return figure_layout(figure, 720)


def overall_timeline_figure(capture: Capture) -> go.Figure:
    if capture.mapped_faults.empty:
        figure = go.Figure()
        figure.update_layout(title="No file-backed fault timeline is available")
        return figure_layout(figure, 420)
    faults = capture.mapped_faults.copy()
    source_counts = faults.groupby("source_label").size().sort_values(ascending=False)
    source_order = source_counts.index.tolist()
    labeled_sources = source_order[:18]
    quiet_sources = source_order[18:]
    quiet_band_height = 4.0
    lane_by_source = {
        source: quiet_band_height + len(labeled_sources) - index - 1
        for index, source in enumerate(labeled_sources)
    }
    if len(quiet_sources) == 1:
        lane_by_source[quiet_sources[0]] = quiet_band_height / 2
    elif quiet_sources:
        for index, source in enumerate(quiet_sources):
            lane_by_source[source] = (
                index * (quiet_band_height - 0.5) / (len(quiet_sources) - 1)
            )
    faults["source_lane"] = faults["source_label"].map(lane_by_source)
    tick_values = [lane_by_source[source] for source in labeled_sources]
    tick_labels = [short_source_label(source) for source in labeled_sources]
    figure = go.Figure()
    for event_type, color, symbol in [
        ("minor", MINOR_COLOR, "circle"),
        ("major", MAJOR_COLOR, "x"),
    ]:
        subset = faults[faults["event_type"] == event_type]
        figure.add_trace(
            go.Scattergl(
                x=subset["elapsed_ms"],
                y=subset["source_lane"],
                mode="markers",
                name=event_type.title(),
                marker=dict(color=color, size=7, symbol=symbol, opacity=0.75),
                customdata=subset[
                    [
                        "thread_name",
                        "source_label",
                        "page_index",
                        "category",
                    ]
                ],
                hovertemplate=(
                    "%{x:.3f} ms<br>%{customdata[1]}<br>"
                    "Page %{customdata[2]:,}<br>"
                    "Thread: %{customdata[0]}<br>"
                    "Category: %{customdata[3]}<extra></extra>"
                ),
            )
        )
    figure.update_layout(
        xaxis_title="Elapsed startup time (ms)",
        yaxis_title="",
    )
    figure.update_yaxes(
        tickmode="array",
        tickvals=tick_values,
        ticktext=tick_labels,
        tickfont=dict(size=10),
        range=[-0.5, quiet_band_height + len(labeled_sources)],
        fixedrange=False,
    )
    return figure_layout(figure, 680, dict(l=150, r=28, t=42, b=64))


def sequence_views(capture: Capture, limit: int = 50) -> list[SequenceView]:
    """Build whole-file and section drill-downs for sequence analysis.

    VDEX files are represented first as complete files using file-relative page
    indices. Their individual DEX payloads remain available as drill-downs, but
    CompactDex shared data is only included in the complete-file view.
    """
    if capture.mapped_faults.empty:
        return []

    faults = capture.mapped_faults.copy()
    faults["_relative_file"] = faults["file_name"].map(
        lambda value: app_relative_path(str(value), capture.package)
    )
    views: list[SequenceView] = []

    vdex_faults = faults[
        faults["file_name"].astype(str).str.lower().str.endswith(".vdex")
    ]
    for relative_file, group in vdex_faults.groupby("_relative_file", sort=False):
        if len(group) < 3:
            continue
        whole_file = group.copy()
        whole_file["sequence_page"] = pd.to_numeric(
            whole_file["page_index"], errors="coerce"
        )
        whole_file = whole_file[whole_file["sequence_page"].notna()]
        if len(whole_file) < 3:
            continue
        whole_file["page_basis"] = "Entire VDEX file"
        views.append(
            SequenceView(
                key=f"vdex-file::{relative_file}",
                label=f"{relative_file} · entire file",
                faults=whole_file,
            )
        )

    for key, group in faults.groupby("unit_key", sort=False):
        is_vdex = group["file_name"].astype(str).str.lower().str.endswith(".vdex").all()
        if is_vdex:
            entries = group["zip_entry_name"].dropna().astype(str)
            if (
                entries.empty
                or entries.str.contains(r"shared.*data", case=False, regex=True).all()
            ):
                continue
        if len(group) < 3:
            continue
        section = group.copy()
        section["sequence_page"] = pd.to_numeric(
            section["section_page"], errors="coerce"
        )
        section = section[section["sequence_page"].notna()]
        if len(section) < 3:
            continue
        section["page_basis"] = (
            "DEX section within VDEX" if is_vdex else "Selected file or section"
        )
        views.append(
            SequenceView(
                key=f"section::{key}",
                label=str(section["source_label"].iloc[0]),
                faults=section,
            )
        )

    def priority(view: SequenceView) -> tuple[int, int, str]:
        label = view.label.lower()
        if view.key.startswith("vdex-file::") and label.endswith(
            "base.vdex · entire file"
        ):
            rank = 0
        elif view.key.startswith("vdex-file::"):
            rank = 1
        elif label.endswith("base.odex"):
            rank = 2
        elif "classes" in label:
            rank = 3
        else:
            rank = 4
        return rank, -len(view.faults), label

    return sorted(views, key=priority)[:limit]


def locality_metrics(capture: Capture) -> pd.DataFrame:
    columns = [
        "unit_key",
        "source",
        "faults",
        "major",
        "minor",
        "unique_pages",
        "span_pages",
        "density",
        "adjacent_rate",
        "near_rate",
        "median_jump_pages",
        "minor_rate",
    ]
    views = sequence_views(capture)
    if not views:
        return pd.DataFrame(columns=columns)
    rows = []
    for view in views:
        ordered = view.faults.sort_values("ts")
        pages = ordered["sequence_page"].astype(int)
        differences = pages.diff().dropna()
        unique_pages = pages.nunique()
        span_pages = int(pages.max() - pages.min() + 1)
        rows.append(
            {
                "unit_key": view.key,
                "source": view.label,
                "faults": len(ordered),
                "major": int(ordered["is_major"].sum()),
                "minor": int((~ordered["is_major"]).sum()),
                "unique_pages": unique_pages,
                "span_pages": span_pages,
                "density": unique_pages / span_pages if span_pages else math.nan,
                "adjacent_rate": (
                    float((differences.abs() == 1).mean())
                    if len(differences)
                    else math.nan
                ),
                "near_rate": (
                    float((differences.abs() <= 32).mean())
                    if len(differences)
                    else math.nan
                ),
                "median_jump_pages": (
                    float(differences.abs().median()) if len(differences) else math.nan
                ),
                "minor_rate": float((~ordered["is_major"]).mean()),
            }
        )
    return pd.DataFrame(rows).sort_values(["major", "faults"], ascending=[False, False])


def sequence_figure(capture: Capture) -> go.Figure:
    views = sequence_views(capture)
    if not views:
        figure = go.Figure()
        figure.update_layout(title="No attributed fault sequence is available")
        return figure_layout(figure, 480)

    figure = go.Figure()
    trace_groups = []
    for key_index, view in enumerate(views):
        group = view.faults.sort_values("ts").reset_index(drop=True)
        group["unit_sequence"] = group.index
        trace_indices = []
        for event_type, color, symbol in [
            ("minor", MINOR_COLOR, "circle"),
            ("major", MAJOR_COLOR, "x"),
        ]:
            subset = group[group["event_type"] == event_type]
            trace_indices.append(len(figure.data))
            figure.add_trace(
                go.Scattergl(
                    x=subset["unit_sequence"],
                    y=subset["sequence_page"],
                    mode="markers",
                    name=event_type.title(),
                    legendgroup=event_type,
                    showlegend=True,
                    visible=key_index == 0,
                    marker=dict(color=color, size=7, symbol=symbol, opacity=0.8),
                    customdata=subset[
                        [
                            "elapsed_ms",
                            "thread_name",
                            "offset",
                            "category",
                            "page_basis",
                        ]
                    ],
                    hovertemplate=(
                        "Fault %{x:,}<br>Page %{y:,}<br>"
                        "%{customdata[0]:.3f} ms<br>"
                        "Thread: %{customdata[1]}<br>"
                        "File offset: %{customdata[2]:,} B<br>"
                        "Category: %{customdata[3]}<br>"
                        "%{customdata[4]}<extra></extra>"
                    ),
                )
            )
        trace_groups.append(trace_indices)

    figure.update_layout(
        xaxis_title="Fault index within selected view",
        yaxis_title=f"Page index ({capture.page_size // 1024} KiB pages)",
        meta={
            "selector_options": [view.label for view in views],
            "traces_per_option": 2,
        },
    )
    return figure_layout(figure, 620, dict(l=64, r=28, t=42, b=64))


def page_cache_figure(capture: Capture) -> go.Figure:
    if capture.page_cache.empty:
        figure = go.Figure()
        figure.update_layout(title="No page-cache insertion events were captured")
        return figure_layout(figure, 420)
    events = capture.page_cache[capture.page_cache["file_name"].notna()].copy()
    if events.empty:
        return figure_layout(go.Figure(), 420)
    top = (
        events.groupby("source_label")["page_count"]
        .sum()
        .sort_values(ascending=False)
        .head(8)
    )
    events = events[events["source_label"].isin(top.index)]
    figure = go.Figure()
    for label in top.index:
        subset = events[events["source_label"] == label]
        figure.add_trace(
            go.Scattergl(
                x=subset["elapsed_ms"],
                y=subset["page_index"],
                mode="markers",
                name=short_source_label(label),
                marker=dict(size=5, opacity=0.65),
                customdata=subset[
                    ["source_label", "page_count", "thread_name", "category"]
                ],
                hovertemplate=(
                    "%{x:.3f} ms<br>%{customdata[0]}<br>"
                    "File page %{y:,}<br>"
                    "Pages inserted: %{customdata[1]}<br>"
                    "Thread: %{customdata[2]}<br>"
                    "Category: %{customdata[3]}<extra></extra>"
                ),
            )
        )
    figure.update_layout(
        xaxis_title="Elapsed startup time (ms)",
        yaxis_title=f"File page index ({capture.page_size // 1024} KiB pages)",
    )
    figure_layout(figure, 620, dict(l=64, r=28, t=36, b=150))
    figure.update_layout(
        legend=dict(
            orientation="h",
            yanchor="top",
            y=-0.18,
            xanchor="left",
            x=0,
            font=dict(size=10),
        )
    )
    return figure


def comparison_summary(base: Capture, test: Capture) -> pd.DataFrame:
    def metrics(capture: Capture) -> dict[str, float]:
        app_faults = capture.mapped_faults[
            capture.mapped_faults["file_name"].str.contains(capture.package, na=False)
        ]
        cache = capture.metadata["cache_verification"]
        return {
            "Startup duration (ms)": capture.metadata["startup"]["duration_ns"]
            / 1_000_000,
            "All major faults": int(
                (capture.all_faults["event_type"] == "major").sum()
            ),
            "File-backed major faults": int(
                (capture.mapped_faults["event_type"] == "major").sum()
            ),
            "App-file major faults": int((app_faults["event_type"] == "major").sum()),
            "App-file minor faults": int((app_faults["event_type"] == "minor").sum()),
            "Resident app pages before launch": int(cache["resident_pages"]),
        }

    base_metrics = metrics(base)
    test_metrics = metrics(test)
    rows = []
    for metric, base_value in base_metrics.items():
        test_value = test_metrics[metric]
        rows.append(
            {
                "Metric": metric,
                base.label: base_value,
                test.label: test_value,
                "Delta": test_value - base_value,
                "Delta %": (
                    (test_value - base_value) / base_value if base_value else math.nan
                ),
            }
        )
    return pd.DataFrame(rows)


def comparison_mismatches(base: Capture, test: Capture) -> list[str]:
    mismatches = []
    for field in COMPARISON_PROVENANCE_FIELDS:
        base_value = base.metadata.get(field)
        test_value = test.metadata.get(field)
        if base_value in (None, "") or test_value in (None, ""):
            mismatches.append(
                f"{field}: required provenance is missing "
                f"({base_value!r}, {test_value!r})"
            )
        elif base_value != test_value:
            mismatches.append(f"{field}: {base_value!r} != {test_value!r}")
    return mismatches


def validate_comparison(base: Capture, test: Capture) -> None:
    mismatches = comparison_mismatches(base, test)
    if mismatches:
        raise RuntimeError(
            "Captures are not a comparable cohort:\n  - " + "\n  - ".join(mismatches)
        )


def comparison_sources_figure(base: Capture, test: Capture) -> go.Figure:
    def app_sources(capture: Capture) -> pd.Series:
        if capture.mapped_faults.empty:
            return pd.Series(dtype="int64")
        faults = capture.mapped_faults[
            capture.mapped_faults["file_name"].str.contains(capture.package, na=False)
            & capture.mapped_faults["is_major"]
        ]
        return faults.groupby("unit_key").size()

    frame = pd.concat(
        [app_sources(base).rename(base.label), app_sources(test).rename(test.label)],
        axis=1,
    ).fillna(0)
    frame["max"] = frame.max(axis=1)
    frame = frame.sort_values("max", ascending=False).head(15).sort_values("max")
    full_labels = [str(key).replace("::", " › ") for key in frame.index]
    display_labels = [short_source_label(label) for label in full_labels]
    figure = go.Figure()
    figure.add_bar(
        y=display_labels,
        x=frame[base.label],
        orientation="h",
        name=base.label,
        marker=dict(color=BASE_COLOR),
        customdata=full_labels,
        hovertemplate="%{customdata}<br>%{x:,} major faults<extra></extra>",
    )
    figure.add_bar(
        y=display_labels,
        x=frame[test.label],
        orientation="h",
        name=test.label,
        marker=dict(color=COMPARE_COLOR),
        customdata=full_labels,
        hovertemplate="%{customdata}<br>%{x:,} major faults<extra></extra>",
    )
    figure.update_layout(
        title="App-file major faults across captures",
        barmode="group",
        xaxis_title="Major fault count",
        yaxis_title="",
    )
    return figure_layout(figure, max(430, 30 * len(frame) + 180))


def comparison_sequence_figure(base: Capture, test: Capture) -> go.Figure:
    if base.mapped_faults.empty or test.mapped_faults.empty:
        figure = go.Figure()
        figure.update_layout(title="No shared attributed sequence is available")
        return figure_layout(figure, 500)
    base_views = {view.key: view for view in sequence_views(base, limit=100)}
    test_views = {view.key: view for view in sequence_views(test, limit=100)}
    shared_keys = set(base_views) & set(test_views)
    if not shared_keys:
        return figure_layout(go.Figure(), 500)
    ordered_base_keys = [view.key for view in sequence_views(base, limit=100)]
    keys = [key for key in ordered_base_keys if key in shared_keys][:40]

    figure = make_subplots(
        rows=2,
        cols=1,
        shared_xaxes=False,
        vertical_spacing=0.12,
        subplot_titles=(base.label, test.label),
    )
    trace_groups = []
    for key_index, key in enumerate(keys):
        indices = []
        for row_index, view in enumerate((base_views[key], test_views[key]), start=1):
            group = view.faults.sort_values("ts").reset_index(drop=True)
            for event_type, color, symbol in [
                ("minor", MINOR_COLOR, "circle"),
                ("major", MAJOR_COLOR, "x"),
            ]:
                subset = group[group["event_type"] == event_type]
                indices.append(len(figure.data))
                figure.add_trace(
                    go.Scattergl(
                        x=subset.index,
                        y=subset["sequence_page"],
                        mode="markers",
                        name=event_type.title(),
                        legendgroup=event_type,
                        showlegend=row_index == 1,
                        visible=key_index == 0,
                        marker=dict(color=color, size=6, symbol=symbol, opacity=0.8),
                        customdata=subset[["elapsed_ms", "thread_name", "page_basis"]],
                        hovertemplate=(
                            "Fault %{x:,}<br>Page %{y:,}<br>"
                            "%{customdata[0]:.3f} ms<br>"
                            "Thread: %{customdata[1]}<br>"
                            "%{customdata[2]}<extra></extra>"
                        ),
                    ),
                    row=row_index,
                    col=1,
                )
        trace_groups.append(indices)

    figure.update_layout(
        meta={
            "selector_options": [base_views[key].label for key in keys],
            "traces_per_option": 4,
        }
    )
    figure.update_xaxes(title_text="Fault index within selected view", row=2, col=1)
    figure.update_yaxes(title_text=f"Page ({base.page_size // 1024} KiB)", row=1, col=1)
    figure.update_yaxes(title_text=f"Page ({test.page_size // 1024} KiB)", row=2, col=1)
    return figure_layout(figure, 800, dict(l=64, r=28, t=56, b=64))


def dataframe_table(frame: pd.DataFrame, formatters: Optional[dict] = None) -> str:
    formatters = formatters or {}
    headers = "".join(f"<th>{html.escape(str(column))}</th>" for column in frame)
    rows = []
    for _, row in frame.iterrows():
        cells = []
        for column in frame:
            value = row[column]
            if column in formatters:
                value = formatters[column](value)
            elif isinstance(value, float):
                value = f"{value:,.2f}"
            cells.append(f"<td>{html.escape(str(value))}</td>")
        rows.append("<tr>" + "".join(cells) + "</tr>")
    return (
        '<div class="table-wrap"><table><thead><tr>'
        + headers
        + "</tr></thead><tbody>"
        + "".join(rows)
        + "</tbody></table></div>"
    )


def metric_cards(capture: Capture) -> str:
    results = capture.metadata["results"]
    app_faults = capture.mapped_faults[
        capture.mapped_faults["file_name"].str.contains(capture.package, na=False)
    ]
    app_major = int(app_faults["is_major"].sum())
    app_minor = int((~app_faults["is_major"]).sum())
    cache = capture.metadata["cache_verification"]
    cards = [
        (
            "App-file major faults",
            compact_number(app_major),
            "Kernel-classified faults that required a blocking major-fault path.",
        ),
        (
            "App-file minor faults",
            compact_number(app_minor),
            "File-backed faults resolved without a major-fault classification.",
        ),
        (
            "File-backed attribution",
            percent(results["file_backed_faults"] / max(1, results["all_faults"])),
            "Share of all startup faults mapped to a regular file.",
        ),
        (
            "Cache state before launch",
            f"{cache['resident_pages']:,} pages",
            f"{cache['fully_evicted_files']}/{cache['files_checked']} checked files were fully evicted.",
        ),
    ]
    return (
        '<div class="metric-grid">'
        + "".join(
            (
                '<div class="metric"><div class="metric-label">'
                + html.escape(label)
                + '</div><div class="metric-value">'
                + html.escape(value)
                + '</div><div class="metric-note">'
                + html.escape(note)
                + "</div></div>"
            )
            for label, value, note in cards
        )
        + "</div>"
    )


def quality_notes(capture: Capture) -> list[str]:
    notes = []
    lost = int(capture.metadata.get("collector_lost", 0))
    notes.append(
        f"The perf collector lost {lost} samples."
        if lost
        else "The perf collector reported zero lost samples."
    )
    cache = capture.metadata["cache_verification"]
    if cache["resident_pages"]:
        notes.append(
            f"{cache['resident_pages']:,} of {cache['total_pages']:,} checked "
            "app-file pages were resident immediately before launch. Per-file "
            "results in cache_residency.csv identify the limitation."
        )
    else:
        notes.append("All checked app-file pages were non-resident before launch.")
    notes.append(
        "Major/minor is the Linux kernel classification after fault handling; "
        "major is not a direct storage-controller measurement."
    )
    notes.append(
        "Page-cache insertion events are shown separately. They are cache fills "
        "(including readahead), not page faults and not minor-fault observations."
    )
    notes.append(
        "Mappings created during collection are resolved against their timestamped "
        "perf MMAP2 records. The post-launch process-map snapshot is used only for "
        "inherited mappings that predate the collector."
    )
    return notes


def plot_div(figure: go.Figure, include_plotlyjs: bool, div_id: str) -> str:
    return pio.to_html(
        figure,
        full_html=False,
        include_plotlyjs=include_plotlyjs,
        div_id=div_id,
        config={
            "displaylogo": False,
            "responsive": True,
            "scrollZoom": True,
            "toImageButtonOptions": {"format": "svg"},
        },
    )


def selector_control(figure: go.Figure, chart_id: str, label: str) -> str:
    metadata = figure.layout.meta or {}
    options = metadata.get("selector_options", [])
    traces_per_option = int(metadata.get("traces_per_option", 0))
    if not options or not traces_per_option:
        return ""
    select_id = f"{chart_id}-selector"
    option_html = "".join(
        f'<option value="{index}">'
        f"{html.escape(str(option).replace('::', ' › '))}</option>"
        for index, option in enumerate(options)
    )
    return f"""
    <div class="chart-control">
      <label for="{select_id}">{html.escape(label)}</label>
      <select id="{select_id}">{option_html}</select>
    </div>
    <script>
    (() => {{
      const bind = () => {{
        const select = document.getElementById({json.dumps(select_id)});
        const chart = document.getElementById({json.dumps(chart_id)});
        const tracesPerOption = {traces_per_option};
        select.addEventListener("change", () => {{
          const first = Number(select.value) * tracesPerOption;
          const selected = Array.from(
            {{length: tracesPerOption}}, (_, index) => first + index
          );
          Plotly.restyle(chart, {{visible: false}}).then(
            () => Plotly.restyle(chart, {{visible: true}}, selected)
          );
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


def build_report(
    capture: Capture,
    output: Path,
    compare: Optional[Capture] = None,
    allow_incomparable: bool = False,
) -> None:
    cohort_mismatches = comparison_mismatches(capture, compare) if compare else []
    if cohort_mismatches and not allow_incomparable:
        validate_comparison(capture, compare)
    figures: list[tuple[str, go.Figure]] = [
        ("all-fault-addresses", all_fault_address_figure(capture)),
        ("top-sources", top_sources_figure(capture)),
        ("timeline", overall_timeline_figure(capture)),
        ("sequence", sequence_figure(capture)),
        ("categories", category_figure(capture)),
        ("page-cache", page_cache_figure(capture)),
    ]
    comparison_frame = None
    if compare:
        figures.extend(
            [
                ("comparison-sources", comparison_sources_figure(capture, compare)),
                (
                    "comparison-sequence",
                    comparison_sequence_figure(capture, compare),
                ),
            ]
        )
        comparison_frame = comparison_summary(capture, compare)

    rendered = {}
    figures_by_name = dict(figures)
    for index, (name, figure) in enumerate(figures):
        rendered[name] = plot_div(
            figure, include_plotlyjs=index == 0, div_id=f"chart-{name}"
        )
    sequence_control = selector_control(
        figures_by_name["sequence"], "chart-sequence", "File or drill-down"
    )
    comparison_sequence_control = (
        selector_control(
            figures_by_name["comparison-sequence"],
            "chart-comparison-sequence",
            "File or drill-down",
        )
        if compare
        else ""
    )

    locality = locality_metrics(capture)
    nearby_label = f"Nearby steps (≤{32 * capture.page_size // 1024} KiB)"
    locality_display = locality.head(20)[
        [
            "source",
            "faults",
            "major",
            "minor",
            "unique_pages",
            "adjacent_rate",
            "near_rate",
            "median_jump_pages",
        ]
    ].rename(
        columns={
            "source": "Source",
            "faults": "Faults",
            "major": "Major",
            "minor": "Minor",
            "unique_pages": "Unique pages",
            "adjacent_rate": "Next-page steps",
            "near_rate": nearby_label,
            "median_jump_pages": "Median jump",
        }
    )
    locality_table = dataframe_table(
        locality_display,
        {
            "Next-page steps": percent,
            nearby_label: percent,
            "Median jump": lambda value: (
                "—" if math.isnan(value) else f"{value:,.1f} pages"
            ),
        },
    )

    top_major = (
        capture.mapped_faults[capture.mapped_faults["is_major"]]
        .groupby(["source_label", "category"])
        .size()
        .sort_values(ascending=False)
        if not capture.mapped_faults.empty
        else pd.Series(dtype="int64")
    )
    top_statement = (
        f"The leading file-backed major-fault source was "
        f"{top_major.index[0][0]} ({int(top_major.iloc[0])} faults)."
        if len(top_major)
        else "No file-backed major faults were captured."
    )
    notes_html = "".join(
        f"<li>{html.escape(note)}</li>" for note in quality_notes(capture)
    )
    max_cache_pages = (
        int(capture.page_cache["page_count"].max())
        if not capture.page_cache.empty
        else 0
    )
    cache_event_granularity = (
        f"one {capture.page_size // 1024} KiB base page"
        if max_cache_pages == 1
        else (
            f"up to {max_cache_pages} base pages "
            f"({max_cache_pages * capture.page_size // 1024} KiB)"
            if max_cache_pages > 1
            else "no pages"
        )
    )

    comparison_html = ""
    if compare and comparison_frame is not None:
        provenance_warning = (
            '<p class="callout"><strong>Comparison override enabled.</strong> '
            + html.escape("; ".join(cohort_mismatches))
            + "</p>"
            if cohort_mismatches
            else ""
        )
        comparison_table = dataframe_table(
            comparison_frame,
            {
                "Delta %": percent,
                "Delta": lambda value: f"{value:+,.1f}",
            },
        )
        comparison_html = f"""
        <section>
          <h2>Comparison: {html.escape(capture.label)} vs. {html.escape(compare.label)}</h2>
          {provenance_warning}
          <p>Logical file keys make randomized install paths comparable. Negative
          deltas mean fewer faults or less startup time in
          {html.escape(compare.label)}.</p>
          {comparison_table}
          <div class="chart">{rendered["comparison-sources"]}</div>
          <p class="chart-note">App-file major faults by logical source.</p>
          {comparison_sequence_control}
          <div class="chart">{rendered["comparison-sequence"]}</div>
          <p class="chart-note">A compact diagonal band is more sequential; vertical
          jumps are accesses to distant file pages.</p>
        </section>
        """

    generated = time_label(capture)
    document = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <link rel="icon" href="data:,">
  <title>Android startup page-fault report — {html.escape(capture.label)}</title>
  <style>
    :root {{
      color-scheme: light;
      --bg: #f6f8fb;
      --surface: #ffffff;
      --ink: #172033;
      --muted: #5f6b7c;
      --line: #dbe2ea;
      --accent: #2563eb;
      --warning: #d97706;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      background: var(--bg);
      color: var(--ink);
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, sans-serif;
      line-height: 1.55;
    }}
    main {{ max-width: 1480px; margin: 0 auto; padding: 48px 24px 80px; }}
    header, section {{
      background: var(--surface);
      border: 1px solid var(--line);
      border-radius: 16px;
      padding: 28px;
      margin-bottom: 22px;
      box-shadow: 0 8px 28px rgba(23, 32, 51, 0.05);
    }}
    h1 {{ margin: 0 0 8px; font-size: clamp(2rem, 4vw, 3.25rem); line-height: 1.08; }}
    h2 {{ margin: 0 0 12px; font-size: 1.55rem; }}
    h3 {{ margin: 24px 0 8px; font-size: 1.08rem; }}
    p {{ max-width: 88ch; }}
    .eyebrow {{ color: var(--accent); font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }}
    .meta {{ color: var(--muted); margin: 0; }}
    .summary {{ font-size: 1.08rem; }}
    .metric-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
      gap: 14px;
      margin-top: 22px;
    }}
    .metric {{ border: 1px solid var(--line); border-radius: 12px; padding: 18px; }}
    .metric-label {{ color: var(--muted); font-size: .86rem; font-weight: 650; }}
    .metric-value {{ font: 700 1.9rem/1.2 ui-monospace, SFMono-Regular, monospace; margin: 7px 0; }}
    .metric-note {{ color: var(--muted); font-size: .86rem; }}
    .chart {{ margin: 18px 0 4px; min-height: 360px; }}
    .chart-note {{ color: var(--muted); font-size: .93rem; margin-top: 0; }}
    .chart-control {{
      display: flex;
      align-items: center;
      gap: 12px;
      margin: 16px 0 -8px;
    }}
    .chart-control label {{
      color: var(--muted);
      font-size: .86rem;
      font-weight: 700;
    }}
    .chart-control select {{
      min-width: min(560px, 80vw);
      max-width: 100%;
      padding: 9px 34px 9px 11px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: white;
      color: var(--ink);
      font: inherit;
    }}
    .table-wrap {{ overflow-x: auto; margin: 18px 0; }}
    table {{ border-collapse: collapse; width: 100%; font-size: .91rem; }}
    th, td {{ border-bottom: 1px solid var(--line); text-align: right; padding: 10px 12px; white-space: nowrap; }}
    th:first-child, td:first-child {{ text-align: left; white-space: normal; min-width: 260px; }}
    th {{ color: var(--muted); font-size: .78rem; text-transform: uppercase; letter-spacing: .04em; }}
    code {{ background: #eef2f7; border-radius: 5px; padding: 2px 5px; }}
    .callout {{ border-left: 4px solid var(--warning); padding-left: 16px; }}
    ul {{ padding-left: 22px; }}
    footer {{ color: var(--muted); font-size: .86rem; padding: 8px 4px; }}
    @media (max-width: 680px) {{
      main {{ padding: 20px 10px 48px; }}
      header, section {{ padding: 20px 14px; border-radius: 12px; }}
      .chart {{ margin-left: -10px; margin-right: -10px; }}
    }}
  </style>
</head>
<body>
<main>
  <header>
    <div class="eyebrow">Android fault visualizer</div>
    <h1>Startup page-fault report</h1>
    <p class="meta">{html.escape(capture.package)} · Android {html.escape(str(capture.metadata["release"]))}
    (API {capture.metadata["sdk"]}) · {capture.page_size // 1024} KiB pages · {html.escape(generated)}</p>
  </header>

  <section>
    <h2>Technical summary</h2>
    <p class="summary"><strong>{html.escape(top_statement)}</strong> Faults and
    page-cache fills are separate; file attribution follows the mapping active
    when each fault occurred.</p>
    {metric_cards(capture)}
  </section>

  <section>
    <h2>Every recorded fault across the address space</h2>
    <p>Every sampled fault by time and virtual address, including anonymous and
    unmapped addresses.</p>
    <div class="chart">{rendered["all-fault-addresses"]}</div>
    <p class="chart-note">Circles are minor; diamonds are major. Filter by mapping
    type and hover for attribution. Virtual addresses can move between runs (ASLR).</p>
  </section>

  <section>
    <h2>Where startup faults came from</h2>
    <p>Regular files and attributed archive/VDEX sections ranked by demanded faults.</p>
    <div class="chart">{rendered["top-sources"]}</div>
    <p class="chart-note">Sorted by major faults, then total faults.</p>
  </section>

  <section>
    <h2>When each file entered the working set</h2>
    <p>Each source has its own lane; labels identify the 18 busiest sources.</p>
    <div class="chart">{rendered["timeline"]}</div>
    <p class="chart-note">No “Other” bucket: quieter files remain distinct in the
    dense lower band. Crosses are major; circles are minor. Hover for the full path.</p>
  </section>

  <section>
    <h2>How sequential the page pattern was</h2>
    <p>Page index in fault order. Diagonal bands are sequential; vertical jumps
    reach distant pages. Complete VDEX views use file-relative pages; APK and
    per-DEX drill-downs use section-relative pages.</p>
    {sequence_control}
    <div class="chart">{rendered["sequence"]}</div>
    <p class="chart-note">Next-page steps move one page forward or back. Nearby
    steps remain within {32 * capture.page_size // 1024} KiB of the preceding
    fault; this summarizes locality, not kernel readahead.</p>
    {locality_table}
  </section>

  <section>
    <h2>Which sections were faulted</h2>
    <p>File types plus exact APK and supported VDEX byte ranges.</p>
    <div class="chart">{rendered["categories"]}</div>
  </section>

  <section>
    <h2>Page-cache fills</h2>
    <p class="callout"><strong>These are not faults.</strong> They are pages inserted
    by reads or readahead and can explain later minor faults.</p>
    <div class="chart">{rendered["page-cache"]}</div>
    <p class="chart-note">The kernel tracepoint reported
    {cache_event_granularity} per insertion event in this capture.</p>
  </section>

  {comparison_html}

  <section>
    <h2>Scope, definitions, and robustness</h2>
    <h3>Measured cohort</h3>
    <p>One process-cold launch (the package was force-stopped), bounded by
    Perfetto's Android startup interval from
    {capture.metadata["startup"]["ts"]:,} ns through
    {capture.metadata["startup"]["ts_end"]:,} ns. The process PID was
    {capture.metadata["pid"]}; software fault events were captured system-wide
    before process creation and filtered by PID and startup time afterward.</p>
    <h3>Definitions</h3>
    <ul>
      <li><strong>Major/minor fault:</strong> the kernel classification emitted
      after fault handling and used for the process's <code>maj_flt</code> and
      <code>min_flt</code> accounting.</li>
      <li><strong>File-backed attribution:</strong> the fault address falls in a
      half-open regular-file mapping active at that timestamp. Perf MMAP2 records
      cover mappings created during collection; <code>/proc/&lt;pid&gt;/maps</code>
      supplies inherited mappings that predate it.</li>
      <li><strong>Page-cache insertion:</strong> a new page or folio added to a
      file's cache; it is not evidence that each inserted page was demanded.</li>
      <li><strong>VDEX/ODEX:</strong> For supported VDEX formats, dex ranges are
      matched to APK entries by ART's stored location checksums. The sequence
      view starts with the complete VDEX; individual DEX payloads are optional
      drill-downs, while shared CompactDex data stays in the complete-file view.
      ODEX pages remain compiled code because byte offsets alone do not identify
      an originating dex.</li>
    </ul>
    <h3>Limitations and checks</h3>
    <ul>{notes_html}</ul>
  </section>

  <section>
    <h2>Recommended next steps</h2>
    <ol>
      <li>Compare at least 10 cold launches per build and report distributions,
      not only one pair; emulator background work changes total startup timing.</li>
      <li>Match compilation mode before assessing R8 ordering. ODEX, VDEX, and
      APK DEX paths answer different runtime configurations.</li>
      <li>Use the selected-file sequence and locality table to choose candidate
      code sections, then verify changes against startup time and app-file major
      faults together.</li>
      <li>On Android 12+, record ART <code>MADV_WILLNEED</code> and platform
      readahead state; prefetching changes what major faults can reveal.</li>
    </ol>
  </section>

  <footer>Generated from {html.escape(str(capture.path))}. Source CSVs and
  capture metadata remain beside this report for audit and reprocessing.</footer>
</main>
</body>
</html>
"""
    output.write_text(document)


def time_label(capture: Capture) -> str:
    return f"startup {capture.metadata['startup']['duration_ns'] / 1_000_000:.1f} ms"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate a self-contained interactive Plotly HTML report"
    )
    parser.add_argument("capture", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--label")
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--compare-label")
    parser.add_argument(
        "--allow-incomparable",
        action="store_true",
        help="Generate a prominently warned comparison despite provenance mismatches",
    )
    args = parser.parse_args()

    capture = read_capture(args.capture, args.label)
    compare = read_capture(args.compare, args.compare_label) if args.compare else None
    output = args.output or args.capture / "report.html"
    output.parent.mkdir(parents=True, exist_ok=True)
    build_report(capture, output, compare, args.allow_incomparable)
    print(f"Report written: {output.resolve()}")


if __name__ == "__main__":
    main()
