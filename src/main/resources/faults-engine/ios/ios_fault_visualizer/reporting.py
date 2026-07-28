from __future__ import annotations

import csv
import html
import json
from pathlib import Path
from typing import Any


def _read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as file:
        return list(csv.DictReader(file))


def _intern_report_data(capture: Path) -> dict[str, Any]:
    metadata = json.loads((capture / "capture_metadata.json").read_text())
    stats = json.loads((capture / "page_fault_stats.json").read_text())
    rows = _read_csv(capture / "page_fault_events.csv")
    frame_ids: dict[str, int] = {}
    frames: list[str] = []
    stack_ids: dict[tuple[int, ...], int] = {}
    stacks: list[list[int]] = []
    events: list[list[Any]] = []

    for row in rows:
        labels = [label for label in row.get("stack", "").split(" ← ") if label]
        frame_sequence = []
        for label in labels:
            identifier = frame_ids.get(label)
            if identifier is None:
                identifier = len(frames)
                frame_ids[label] = identifier
                frames.append(label)
            frame_sequence.append(identifier)
        stack_key = tuple(frame_sequence)
        stack_id = stack_ids.get(stack_key)
        if stack_id is None:
            stack_id = len(stacks)
            stack_ids[stack_key] = stack_id
            stacks.append(frame_sequence)
        events.append(
            [
                int(row["event_index"]),
                float(row["time_since_first_fault_ms"]),
                int(row["address"]),
                row["address_hex"],
                1 if row["fault_class"] == "Major" else 0,
                row["operation"],
                row.get("thread", ""),
                int(row["tid"]) if row.get("tid") else None,
                row.get("faulting_frame", ""),
                row.get("faulting_binary", ""),
                row.get("first_app_frame", ""),
                int(row["duration_ns"]),
                stack_id,
            ]
        )

    major_summary = _read_csv(capture / "major_page_fault_code_summary.csv")[:25]
    cache_rows = _read_csv(capture / "cache_residency.csv")
    return {
        "metadata": metadata,
        "stats": stats,
        "frames": frames,
        "stacks": stacks,
        "events": events,
        "majorSummary": major_summary,
        "cacheRows": cache_rows,
    }


def build_report(
    capture: Path,
    output: Path,
    display_capture: Path | None = None,
) -> None:
    capture = capture.resolve()
    display_capture = (display_capture or capture).resolve()
    data = _intern_report_data(capture)
    metadata = data["metadata"]
    title = (
        f"{metadata.get('app_binary_name') or metadata.get('bundle_id')} "
        "startup page faults"
    )
    payload = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    document = REPORT_TEMPLATE
    document = document.replace("__TITLE__", html.escape(title))
    document = document.replace("__CAPTURE_PATH__", html.escape(str(display_capture)))
    plotly_js = Path(__file__).with_name("assets").joinpath("plotly.min.js").read_text()
    document = document.replace("__PLOTLY_JS__", plotly_js)
    document = document.replace("__REPORT_DATA__", payload)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(document, encoding="utf-8")


REPORT_TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="icon" href="data:,">
<title>__TITLE__</title>
<style>
:root{--ink:#172033;--muted:#65718a;--line:#dfe4ec;--paper:#fff;--wash:#f6f7f9;--blue:#2563eb;--orange:#f59e0b;--orange-dark:#92400e}
*{box-sizing:border-box} body{margin:0;background:var(--wash);color:var(--ink);font:14px/1.45 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
main{max-width:1500px;margin:0 auto;padding:42px 28px 80px} h1{font-size:34px;letter-spacing:-.025em;margin:0 0 8px} h2{font-size:21px;margin:0} h3{font-size:15px;margin:0 0 10px} p{color:var(--muted)}
.eyebrow{text-transform:uppercase;letter-spacing:.13em;color:var(--blue);font-weight:700;font-size:12px}.subtitle{font-size:16px;margin:0 0 24px}
.cards{display:grid;grid-template-columns:repeat(5,minmax(150px,1fr));gap:12px;margin:24px 0}.card,.panel{background:var(--paper);border:1px solid var(--line);border-radius:12px;box-shadow:0 1px 2px #1018280d}
.card{padding:16px}.card strong{display:block;font-size:25px;letter-spacing:-.03em}.card span{color:var(--muted)}
.panel{padding:20px;margin:14px 0}.panel-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:12px}
.panel-head p{margin:4px 0 0}.legend{white-space:nowrap;color:var(--muted)}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin:0 6px 0 14px}.diamond{display:inline-block;width:9px;height:9px;background:var(--orange);transform:rotate(45deg);margin:0 7px 0 16px;border:1px solid var(--orange-dark)}
.chart-wrap{position:relative;width:100%;height:450px}.chart-wrap.address{height:520px}.chart-wrap canvas{width:100%;height:100%;display:block;cursor:crosshair}.plotly-chart{width:100%;height:100%}
.count-note{margin:0 0 20px;padding:11px 14px;border-left:4px solid var(--blue);background:#eff6ff;color:#334155}
.tooltip{position:fixed;z-index:10;display:none;pointer-events:none;max-width:440px;background:#111827;color:white;padding:9px 11px;border-radius:8px;box-shadow:0 8px 24px #0004;font-size:12px}
.controls{display:flex;align-items:center;gap:16px;flex-wrap:wrap;background:#f8fafc;border:1px solid var(--line);border-radius:9px;padding:10px 12px;margin-bottom:12px}
.controls label{display:flex;align-items:center;gap:6px;color:var(--muted)}.controls input[type=search]{min-width:280px;padding:7px 10px;border:1px solid #cbd5e1;border-radius:7px;background:white}
button{font:inherit}.button{border:1px solid #cbd5e1;background:white;border-radius:7px;padding:7px 11px;color:var(--ink);cursor:pointer}.button:hover{border-color:#94a3b8}
.range{font-variant-numeric:tabular-nums;color:var(--muted);margin-left:auto}
.explorer{display:grid;grid-template-columns:minmax(400px,1fr) minmax(420px,1.1fr);gap:14px}.fault-list{height:560px;overflow:auto;overscroll-behavior:contain;position:relative;border:1px solid var(--line);border-radius:9px;background:#fbfcfd}.fault-rows{position:absolute;inset:0 0 auto 0}
.fault-row{position:absolute;left:0;right:0;height:64px;border:0;border-bottom:1px solid #edf0f4;background:transparent;text-align:left;padding:8px 12px;cursor:pointer;color:var(--ink)}.fault-row:hover,.fault-row.selected{background:#eff6ff}.fault-row .top{display:flex;gap:8px;align-items:center}.fault-row .frame{display:block;color:var(--muted);white-space:nowrap;text-overflow:ellipsis;overflow:hidden;margin-top:3px}
.pill{border-radius:99px;padding:1px 7px;font-size:11px;font-weight:700}.pill.minor{color:#1d4ed8;background:#dbeafe}.pill.major{color:#92400e;background:#ffedd5}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
.detail{height:560px;overflow:auto;overscroll-behavior:contain;border:1px solid var(--line);border-radius:9px;padding:16px}.detail-grid{display:grid;grid-template-columns:130px 1fr;gap:6px 12px}.detail-grid dt{color:var(--muted)}.detail-grid dd{margin:0;overflow-wrap:anywhere}
.stack{list-style:none;padding:0;margin:14px 0}.stack li{position:relative;margin-left:12px;padding:6px 10px 6px 22px;border-left:2px solid #cbd5e1;overflow-wrap:anywhere}.stack li:before{content:"";position:absolute;left:-5px;top:13px;width:8px;height:8px;background:white;border:2px solid var(--blue);border-radius:50%}.stack li:last-child{font-weight:650}.stack li:last-child:before{border-color:var(--orange);background:#ffedd5}
table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:9px 10px;border-bottom:1px solid #e8ecf1;vertical-align:top}th{color:var(--muted);font-size:12px}td.frame-cell{max-width:680px;overflow-wrap:anywhere}
.notice{padding:13px 15px;border-radius:9px;background:#fff7ed;border:1px solid #fed7aa;color:#9a3412}.downloads a{display:inline-block;margin:0 14px 6px 0;color:var(--blue)}
footer{margin-top:28px;color:var(--muted);font-size:12px;overflow-wrap:anywhere}
@media(max-width:900px){.cards{grid-template-columns:repeat(2,1fr)}.explorer{grid-template-columns:1fr}.controls input[type=search]{min-width:180px}.range{margin-left:0}}
</style>
</head>
<body>
<main>
<div class="eyebrow">iOS Fault Visualizer</div>
<h1 id="title">__TITLE__</h1>
<p class="subtitle" id="subtitle"></p>
<div class="cards" id="cards"></div>
<p class="count-note" id="countNote"></p>

<section class="panel">
  <div class="panel-head"><div><h2>Fault address timeline</h2><p>Plotly scatter plot of time since the first recorded target-process fault versus virtual page address. Box-zoom, pan, hover, and click inspection are available from the chart.</p></div><div class="legend"><span class="dot" style="background:var(--blue)"></span>Minor <span class="diamond"></span>Major</div></div>
  <div class="chart-wrap address"><div class="plotly-chart" id="addressChart"></div></div>
</section>

<section class="panel">
  <div class="panel-head"><div><h2>Chronological stack chart</h2><p>Firefox-profiler-style stack columns. The root is at the bottom and the faulting frame is at the top. Horizontal position is fault order, so equal-time events remain distinct; use the details view for exact time.</p></div><div class="legend">Click a column to inspect its complete stack</div></div>
  <div class="controls">
    <label><input id="minorToggle" type="checkbox" checked> Minor</label>
    <label><input id="majorToggle" type="checkbox" checked> Major</label>
    <input id="search" type="search" placeholder="Filter stack, binary, thread, or operation">
    <button class="button" id="resetZoom">Reset zoom</button>
    <span class="range" id="rangeLabel"></span>
  </div>
  <div class="chart-wrap"><canvas id="stackChart"></canvas></div>
</section>

<section class="panel">
  <div class="panel-head"><div><h2>Fault stacks in order</h2><p id="listSummary"></p></div><div class="legend">Leaf-first capture, displayed root → faulting frame</div></div>
  <div class="explorer">
    <div class="fault-list" id="faultList"><div id="faultSpacer"></div><div class="fault-rows" id="faultRows"></div></div>
    <div class="detail" id="detail"></div>
  </div>
</section>

<section class="panel">
  <div class="panel-head"><div><h2 id="majorTableTitle">Application code-ordering candidates</h2><p id="majorTableSummary"></p></div></div>
  <div style="overflow:auto"><table><thead><tr><th>Count</th><th>First</th><th>Faulting frame</th><th>Binary</th><th>First app frame</th><th>Example address</th></tr></thead><tbody id="majorTable"></tbody></table></div>
</section>

<section class="panel">
  <h2>Measurement quality and artifacts</h2>
  <div id="quality"></div>
  <p class="downloads"><a href="faults.trace">Instruments trace</a><a href="page_fault_events.csv">All faults CSV</a><a href="major_page_fault_events.csv">Major stacks CSV</a><a href="page_faults.sqlite">SQLite</a><a href="capture_metadata.json">Metadata</a><a href="cache_residency.csv">Cache residency</a></p>
</section>
<footer>Generated from __CAPTURE_PATH__. Source paths, device identifiers, and app-container paths may be present; redact before sharing externally.</footer>
</main>
<div class="tooltip" id="tooltip"></div>
<script>__PLOTLY_JS__</script>
<script>
const DATA=__REPORT_DATA__;
const E={index:0,time:1,address:2,hex:3,major:4,operation:5,thread:6,tid:7,frame:8,binary:9,app:10,duration:11,stack:12};
const BLUE="#2563eb", ORANGE="#f59e0b", INK="#172033", MUTED="#65718a", GRID="#e2e8f0";
const fullRangeEnd=DATA.events.reduce((maximum,event)=>Math.max(maximum,event[E.time]),1);
let rangeStart=0, rangeEnd=fullRangeEnd, orderStart=null, orderEnd=null, selected=null, filtered=[];
const tooltip=document.getElementById("tooltip");
const fmt=n=>new Intl.NumberFormat().format(n);
const escapeHtml=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#039;"}[c]));
function stackLabels(event){return DATA.stacks[event[E.stack]].map(id=>DATA.frames[id])}
const searchTexts=DATA.events.map(e=>([e[E.operation],e[E.thread],e[E.frame],e[E.binary],e[E.app],...stackLabels(e)].join(" ").toLowerCase()));
function setHeader(){
 const m=DATA.metadata,s=DATA.stats,c=s.class_counts||{};
 const windowText=s.analysis_window_ms?`${fmt(s.analysis_window_ms)} ms startup window`:"full exported trace";
 document.getElementById("subtitle").textContent=`${m.target_kind} · ${m.target_name} · PID ${m.target_pid} · last included fault at ${s.capture_span_ms.toFixed(1)} ms · ${windowText}`;
 const cards=[["Analyzed faults",fmt(s.event_count)],["Minor",fmt(c.Minor||0)],["Major",fmt(c.Major||0)],["Major stacks",`${fmt(s.major_faults_with_stack||0)} / ${fmt(c.Major||0)}`],["Page size",s.page_size_bytes?`${fmt(s.page_size_bytes/1024)} KiB`:"—"]];
 document.getElementById("cards").innerHTML=cards.map(([l,v])=>`<div class=card><strong>${v}</strong><span>${l}</span></div>`).join("");
 document.getElementById("countNote").innerHTML=`The ${windowText} contains <strong>${fmt(s.event_count)}</strong> supported target-process VM faults: <strong>${fmt(c.Minor||0)}</strong> blue minor faults and <strong>${fmt(c.Major||0)}</strong> orange major page-ins. Major-only tables and stack counts below do not represent the analyzed fault total.`;
}
function resizeCanvas(canvas){
 const dpr=Math.min(devicePixelRatio||1,2), rect=canvas.getBoundingClientRect();
 const w=Math.max(300,Math.round(rect.width*dpr)),h=Math.max(180,Math.round(rect.height*dpr));
 if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h}
 return {ctx:canvas.getContext("2d"),w,h,dpr};
}
function visibleEvents(){
 const minor=document.getElementById("minorToggle").checked,major=document.getElementById("majorToggle").checked,q=document.getElementById("search").value.trim().toLowerCase();
 filtered=DATA.events.filter((e,i)=>e[E.time]>=rangeStart&&e[E.time]<=rangeEnd&&(orderStart===null||e[E.index]>=orderStart&&e[E.index]<=orderEnd)&&(e[E.major]?major:minor)&&(!q||searchTexts[i].includes(q)));
 const scope=orderStart===null?`${rangeStart.toFixed(1)}–${rangeEnd.toFixed(1)} ms`:`faults #${orderStart}–#${orderEnd}`;
 document.getElementById("rangeLabel").textContent=`${scope} · ${fmt(filtered.length)} faults`;
 document.getElementById("listSummary").textContent=`${fmt(filtered.length)} matching events in chronological order.`;
 return filtered;
}
function tickHex(value){return "0x"+Math.round(value).toString(16)}
function plotlyTrace(events,name,color,symbol,opacity){
 return {type:"scatter",mode:"markers",name,x:events.map(e=>e[E.time]),y:events.map(e=>e[E.address]),customdata:events.map(e=>[e[E.hex],e[E.operation],e[E.frame]||"unresolved",e[E.index]]),marker:{color,size:symbol==="diamond"?9:5,symbol,opacity,line:symbol==="diamond"?{color:"#92400e",width:1}:undefined},hovertemplate:`<b>${name}</b> · %{customdata[1]}<br>%{x:.3f} ms · %{customdata[0]}<br>%{customdata[2]}<extra></extra>`};
}
function drawAddress(){
 const chart=document.getElementById("addressChart");
 const events=filtered.length?filtered:visibleEvents(), source=events.length?events:DATA.events;
 let yMin=source.reduce((minimum,event)=>Math.min(minimum,event[E.address]),Infinity),yMax=source.reduce((maximum,event)=>Math.max(maximum,event[E.address]),-Infinity);if(yMax===yMin)yMax++;
 const minor=events.filter(e=>!e[E.major]),major=events.filter(e=>e[E.major]),tickValues=Array.from({length:6},(_,i)=>yMin+(yMax-yMin)*i/5);
 const traces=[plotlyTrace(minor,`Minor (${fmt(minor.length)})`,BLUE,"circle",.42),plotlyTrace(major,`Major (${fmt(major.length)})`,ORANGE,"diamond",.95)];
 const layout={margin:{l:130,r:24,t:16,b:58},paper_bgcolor:"#fff",plot_bgcolor:"#fff",font:{family:'Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif',color:INK},legend:{orientation:"h",x:0,y:1.08},hovermode:"closest",dragmode:"zoom",xaxis:{title:"Time since first fault (ms)",gridcolor:GRID,range:[rangeStart,rangeEnd],zeroline:false},yaxis:{title:"Virtual page address",gridcolor:GRID,tickmode:"array",tickvals:tickValues,ticktext:tickValues.map(tickHex),zeroline:false},uirevision:`${rangeStart}:${rangeEnd}:${minor.length}:${major.length}`};
 Plotly.react(chart,traces,layout,{responsive:true,displaylogo:false,scrollZoom:true,modeBarButtonsToRemove:["lasso2d","select2d"]});
 if(!chart._faultClickAttached){chart.on("plotly_click",event=>{const index=event.points[0]?.customdata?.[3],fault=DATA.events.find(e=>e[E.index]===index);if(fault)selectEvent(fault,true)});chart._faultClickAttached=true}
}
function colorFor(id){let x=(id+1)*2654435761>>>0;return `hsl(${x%360} 52% ${44+(x%13)}%)`}
function drawStacks(){
 const canvas=document.getElementById("stackChart"),{ctx,w,h,dpr}=resizeCanvas(canvas);ctx.clearRect(0,0,w,h);
 const events=filtered.length?filtered:visibleEvents(),p={l:54*dpr,r:18*dpr,t:18*dpr,b:42*dpr},pw=w-p.l-p.r,ph=h-p.t-p.b;
 ctx.fillStyle="#fff";ctx.fillRect(0,0,w,h);ctx.strokeStyle=GRID;ctx.fillStyle=MUTED;ctx.font=`${12*dpr}px ui-monospace`;
 for(let i=0;i<=6;i++){const px=p.l+pw*i/6,event=events[Math.min(events.length-1,Math.round((events.length-1)*i/6))];ctx.beginPath();ctx.moveTo(px,p.t);ctx.lineTo(px,p.t+ph);ctx.stroke();ctx.textAlign="center";ctx.fillText(event?`#${event[E.index]}`:"—",px,h-15*dpr)}
 const maxDepth=events.reduce((maximum,e)=>Math.max(maximum,DATA.stacks[e[E.stack]].length),1),row=Math.min(13*dpr,ph/maxDepth),step=pw/Math.max(events.length,1),width=Math.max(1*dpr,Math.min(10*dpr,step));
 for(let i=0;i<events.length;i++){const e=events[i],stack=DATA.stacks[e[E.stack]].slice().reverse(),x=p.l+i*step;
   for(let depth=0;depth<stack.length;depth++){const y=p.t+ph-(depth+1)*row;ctx.fillStyle=colorFor(stack[depth]);ctx.globalAlpha=.8;ctx.fillRect(x,y,width,Math.max(.5*dpr,row-.5*dpr))}
   ctx.globalAlpha=1;ctx.fillStyle=e[E.major]?ORANGE:BLUE;ctx.fillRect(x,p.t,Math.max(width,1*dpr),2*dpr);
 }
 ctx.fillStyle=MUTED;ctx.textAlign="right";ctx.fillText("root",p.l-8*dpr,p.t+ph-row/2);ctx.fillText("leaf",p.l-8*dpr,Math.max(p.t+row/2,p.t+ph-Math.min(maxDepth*row,ph)+row/2));
 canvas._chart={events,p,pw,step};
}
function renderList(reset=false){
 const list=document.getElementById("faultList"),rows=document.getElementById("faultRows"),spacer=document.getElementById("faultSpacer"),rowH=64;
 spacer.style.height=`${filtered.length*rowH}px`;if(reset)list.scrollTop=0;
 const start=Math.max(0,Math.floor(list.scrollTop/rowH)-5),end=Math.min(filtered.length,start+Math.ceil(list.clientHeight/rowH)+11);
 rows.innerHTML=filtered.slice(start,end).map((e,j)=>{const cls=e[E.major]?"major":"minor",label=e[E.major]?"Major":"Minor";return `<button class="fault-row ${selected===e?"selected":""}" style="top:${(start+j)*rowH}px" data-pos="${start+j}"><span class=top><span class="pill ${cls}">${label}</span><span class=mono>${e[E.time].toFixed(3)} ms</span><span class=mono>${e[E.hex]}</span><span>${escapeHtml(e[E.operation])}</span></span><span class=frame>${escapeHtml(e[E.frame]||"(unresolved)")}${e[E.binary]?` · ${escapeHtml(e[E.binary])}`:""}</span></button>`}).join("");
 rows.querySelectorAll("button").forEach(button=>button.onclick=()=>selectEvent(filtered[Number(button.dataset.pos)]));
}
function selectEvent(event,scroll=false){
 selected=event;const labels=stackLabels(event).slice().reverse();
 document.getElementById("detail").innerHTML=`<h3>Fault #${event[E.index]} · <span class="pill ${event[E.major]?"major":"minor"}">${event[E.major]?"Major":"Minor"}</span></h3><dl class=detail-grid><dt>Time</dt><dd>${event[E.time].toFixed(6)} ms</dd><dt>Page address</dt><dd class=mono>${event[E.hex]}</dd><dt>Operation</dt><dd>${escapeHtml(event[E.operation])}</dd><dt>Duration</dt><dd>${(event[E.duration]/1000).toFixed(3)} µs</dd><dt>Thread</dt><dd>${escapeHtml(event[E.thread]||"—")} ${event[E.tid]?"("+event[E.tid]+")":""}</dd><dt>Faulting frame</dt><dd>${escapeHtml(event[E.frame]||"unresolved")}</dd><dt>Binary</dt><dd>${escapeHtml(event[E.binary]||"—")}</dd><dt>First app frame</dt><dd>${escapeHtml(event[E.app]||"—")}</dd></dl><h3 style="margin-top:18px">Complete stack · root → faulting frame</h3>${labels.length?`<ol class=stack>${labels.map(label=>`<li>${escapeHtml(label)}</li>`).join("")}</ol>`:"<p>No stack was captured for this event.</p>"}`;
 if(scroll){const pos=filtered.indexOf(event);if(pos>=0)document.getElementById("faultList").scrollTop=Math.max(0,pos*64-160)}
 renderList();
}
function stackEventAtOffset(canvas,offsetX){const chart=canvas._chart;if(!chart||!chart.events.length)return null;const ratio=Math.max(0,Math.min(.999999,(offsetX-54)/(canvas.clientWidth-54-18)));return chart.events[Math.floor(ratio*chart.events.length)]}
function attachStackCanvas(canvas){
 let down=null;
 canvas.addEventListener("pointerdown",e=>down=e.offsetX);
 canvas.addEventListener("pointerup",e=>{if(down===null)return;const a=stackEventAtOffset(canvas,down),b=stackEventAtOffset(canvas,e.offsetX);if(Math.abs(e.offsetX-down)>8&&a&&b){orderStart=Math.min(a[E.index],b[E.index]);orderEnd=Math.max(a[E.index],b[E.index]);update(true)}else{const event=stackEventAtOffset(canvas,e.offsetX);if(event)selectEvent(event,true)}down=null});
 canvas.addEventListener("dblclick",()=>resetZoom());
 canvas.addEventListener("pointermove",e=>{const event=stackEventAtOffset(canvas,e.offsetX);if(!event)return;tooltip.style.display="block";tooltip.style.left=`${Math.min(innerWidth-450,e.clientX+14)}px`;tooltip.style.top=`${Math.min(innerHeight-110,e.clientY+14)}px`;tooltip.innerHTML=`<strong>Fault #${event[E.index]} · ${event[E.major]?"Major":"Minor"} · ${escapeHtml(event[E.operation])}</strong><br>${event[E.time].toFixed(3)} ms · <span class=mono>${event[E.hex]}</span><br>${escapeHtml(event[E.frame]||"unresolved")}`});
 canvas.addEventListener("pointerleave",()=>tooltip.style.display="none");
}
function resetZoom(){rangeStart=0;rangeEnd=fullRangeEnd;orderStart=null;orderEnd=null;update(true)}
function update(resetList=false){visibleEvents();drawAddress();drawStacks();renderList(resetList);if(selected&&!filtered.includes(selected))selected=null;if(!selected&&filtered.length)selectEvent(filtered.find(e=>e[E.major])||filtered[0]);if(!filtered.length)document.getElementById("detail").innerHTML="<p>No faults match the current filters.</p>"}
function renderMajorTable(){const candidateFaults=DATA.majorSummary.reduce((sum,row)=>sum+Number(row.major_fault_count),0);document.getElementById("majorTableTitle").textContent=`Application code-ordering candidates · ${fmt(candidateFaults)} faults`;document.getElementById("majorTableSummary").textContent=`Only major events whose actual faulting binary is inside the captured app bundle are included. Generic entry points and unresolved frames are excluded. Candidates are ranked by count, then first-touch time; all ${fmt(DATA.stats.event_count)} analyzed faults remain in the timeline and ordered list.`;document.getElementById("majorTable").innerHTML=DATA.majorSummary.map(row=>`<tr><td>${fmt(Number(row.major_fault_count))}</td><td>${Number(row.first_fault_ms).toFixed(3)} ms</td><td class=frame-cell>${escapeHtml(row.faulting_frame)}</td><td>${escapeHtml(row.faulting_binary)}</td><td class=frame-cell>${escapeHtml(row.first_app_frame||"—")}</td><td class=mono>${escapeHtml(row.example_address_hex)}</td></tr>`).join("")||"<tr><td colspan=6>No verified app-owned code-ordering candidates were captured.</td></tr>"}
function renderQuality(){const m=DATA.metadata,s=DATA.stats,cache=m.cache||{},sim=m.target_kind==="simulator",confidence=String(cache.confidence||""),warnings=m.capture_quality_warnings||[];let cacheText=confidence==="confirmed-evicted"?"Complete app-bundle residency checks found zero resident pages immediately before launch.":confidence==="threshold-met-partially-resident"?"Complete app-bundle checks met the configured threshold, but some pages remained resident; treat this as a partially warm capture.":confidence.startsWith("best-effort")?"The physical-device procedure is best-effort. Stock iOS does not expose a supported global page-cache flush or residency API.":confidence==="none"?"No page-cache preparation was requested.":"Cache eviction was attempted but could not be independently confirmed.";
 const warningText=warnings.length?`<li>${fmt(warnings.length)} xctrace warning line(s) were recorded in capture metadata; review them before relying on symbolication.</li>`:"";
 document.getElementById("quality").innerHTML=`<p class=notice><strong>${escapeHtml(cache.procedure||"Cache procedure not recorded")}:</strong> ${escapeHtml(cacheText)}</p><ul><li>${escapeHtml(s.classification_note)}</li><li>${sim?"Simulator faults are macOS host VM/cache behavior and are not equivalent to physical-device storage page-ins.":"Physical-device Virtual Memory Trace was used; file-backed page-ins are the closest available Instruments signal, but the major/minor names remain analytical buckets."}</li><li>${fmt(s.major_faults_with_stack||0)} of ${fmt((s.class_counts||{}).Major||0)} major page-ins include a captured stack; ${fmt(s.major_faults_with_app_frame||0)} reach an app frame, and ${fmt(s.major_faults_with_bundle_owned_faulting_binary||0)} have a verified app-bundle faulting binary.</li>${warningText}</ul>`}
setHeader();renderMajorTable();renderQuality();attachStackCanvas(document.getElementById("stackChart"));
document.getElementById("minorToggle").onchange=()=>update(true);document.getElementById("majorToggle").onchange=()=>update(true);document.getElementById("search").oninput=()=>update(true);document.getElementById("resetZoom").onclick=resetZoom;document.getElementById("faultList").onscroll=()=>renderList();addEventListener("resize",()=>{drawAddress();drawStacks()});update();
</script>
</body>
</html>
"""
