package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path

internal class IosFaultReport(
    private val engineRoot: Path,
) {
    fun build(
        capture: Path,
        output: Path,
    ) {
        val metadata = Json.readMap(capture.resolve("capture_metadata.json"))
        val stats = Json.readMap(capture.resolve("page_fault_stats.json"))
        val rows = Csv.read(capture.resolve("page_fault_events.csv"))
        val summaries = Csv.read(capture.resolve("major_page_fault_code_summary.csv")).take(25)
        val frameIds = linkedMapOf<String, Int>()
        val stacks = mutableListOf<List<Int>>()
        val stackIds = linkedMapOf<List<Int>, Int>()
        val events =
            rows.map { row ->
                val stack =
                    row["stack"]
                        .orEmpty()
                        .split(" ← ")
                        .filter(String::isNotBlank)
                        .map { frameIds.getOrPut(it) { frameIds.size } }
                val stackId = stackIds.getOrPut(stack) { stacks.size.also { stacks += stack } }
                listOf(
                    row.getValue("event_index").toInt(),
                    row.getValue("time_since_first_fault_ms").toDouble(),
                    row.getValue("address"),
                    row.getValue("address_hex"),
                    row.getValue("fault_class") == "Major",
                    row.getValue("operation"),
                    row["thread"].orEmpty(),
                    row["tid"]?.toLongOrNull(),
                    row["faulting_frame"].orEmpty(),
                    row["faulting_binary"].orEmpty(),
                    row["first_app_frame"].orEmpty(),
                    row.getValue("duration_ns").toLong(),
                    stackId,
                )
            }
        val frames = frameIds.entries.sortedBy { it.value }.map { it.key }
        val data =
            mapOf(
                "metadata" to metadata,
                "stats" to stats,
                "frames" to frames,
                "stacks" to stacks,
                "events" to events,
                "majorSummary" to summaries,
            )
        val title = "${metadata["app_binary_name"] ?: metadata["bundle_id"]} startup page faults"
        val plotly = Files.readString(engineRoot.resolve("ios/ios_fault_visualizer/assets/plotly.min.js"))
        val payload = Json.mapper.writeValueAsString(data).replace("</", "<\\/")
        val html =
            template
                .replace("__TITLE__", escapeHtml(title))
                .replace("__PLOTLY__", plotly)
                .replace("__DATA__", payload)
                .replace(
                    "__CAPTURE__",
                    escapeHtml(metadata["published_output"]?.toString() ?: capture.toAbsolutePath().normalize().toString()),
                )
        Files.createDirectories(output.parent)
        Files.writeString(output, html)
    }

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private val template =
        """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <link rel="icon" href="data:,"><title>__TITLE__</title>
        <style>
        :root{--ink:#172033;--muted:#65718a;--line:#dfe4ec;--blue:#2563eb;--orange:#f59e0b}
        *{box-sizing:border-box}body{margin:0;background:#f6f7f9;color:var(--ink);font:14px/1.45 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
        main{max-width:1500px;margin:auto;padding:38px 26px 72px}h1{font-size:34px;margin:0 0 8px}h2{margin:0;font-size:21px}p{color:var(--muted)}
        .eyebrow{text-transform:uppercase;letter-spacing:.13em;color:var(--blue);font-weight:700;font-size:12px}.subtitle{font-size:16px;margin-top:0}
        .cards{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin:22px 0}.card,.panel{background:white;border:1px solid var(--line);border-radius:12px;box-shadow:0 1px 2px #1018280d}
        .card{padding:16px}.card strong{display:block;font-size:25px}.card span{color:var(--muted)}.panel{padding:20px;margin:14px 0}
        .panel-head{display:flex;justify-content:space-between;gap:18px;margin-bottom:12px}.panel-head p{margin:4px 0 0}.plot{height:520px}
        #stack{height:480px;width:100%;display:block;border:1px solid var(--line);border-radius:8px;cursor:crosshair}.controls{display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
        input[type=search]{min-width:300px;padding:8px;border:1px solid #cbd5e1;border-radius:7px}.legend{color:var(--muted)}
        .explorer{display:grid;grid-template-columns:minmax(360px,.9fr) minmax(440px,1.1fr);gap:14px}.list,.detail{height:520px;overflow:auto;overscroll-behavior:contain;border:1px solid var(--line);border-radius:8px}
        .row{display:block;width:100%;padding:9px 12px;border:0;border-bottom:1px solid #edf0f4;background:white;text-align:left;cursor:pointer}.row:hover,.row.selected{background:#eff6ff}
        .pill{display:inline-block;border-radius:99px;padding:1px 7px;font-size:11px;font-weight:700;margin-right:7px}.minor{color:#1d4ed8;background:#dbeafe}.major{color:#92400e;background:#ffedd5}
        .frame{display:block;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.detail{padding:16px}.stack{list-style:none;padding:0}.stack li{padding:6px 10px;border-left:2px solid #cbd5e1;margin-left:10px}.stack li:last-child{font-weight:700;border-color:var(--orange)}
        table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px;border-bottom:1px solid #e8ecf1;vertical-align:top}th{color:var(--muted);font-size:12px}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
        footer{color:var(--muted);font-size:12px;margin-top:24px}@media(max-width:900px){.cards{grid-template-columns:repeat(2,1fr)}.explorer{grid-template-columns:1fr}}
        </style></head><body><main><div class="eyebrow">mperf · iOS faults</div><h1>__TITLE__</h1><p class="subtitle" id="subtitle"></p><div class="cards" id="cards"></div>
        <section class="panel"><div class="panel-head"><div><h2>All page faults</h2><p>Virtual fault address over startup time. Every retained target-PID event is shown.</p></div><div class="legend">● Minor &nbsp; ◆ Major</div></div><div id="address" class="plot"></div></section>
        <section class="panel"><div class="panel-head"><div><h2>Fault stacks in startup order</h2><p>Each column is one fault. Root frames are at the bottom; the actual faulting frame is at the top.</p></div><div class="legend" id="visible"></div></div>
        <div class="controls"><label><input id="minor" type="checkbox" checked> Minor</label><label><input id="major" type="checkbox" checked> Major</label><input id="search" type="search" placeholder="Filter frame, binary, thread, or operation"></div><canvas id="stack"></canvas></section>
        <section class="panel"><div class="panel-head"><div><h2>Fault details</h2><p>Chronological across all target threads; equal-time events remain distinct.</p></div></div><div class="explorer"><div class="list" id="list"></div><div class="detail" id="detail"></div></div></section>
        <section class="panel"><div class="panel-head"><div><h2>Application code-ordering candidates</h2><p>Only major events whose actual faulting binary is inside this app bundle. Ranked by count, then first touch.</p></div></div><div style="overflow:auto"><table><thead><tr><th>Count</th><th>First touch</th><th>Faulting frame</th><th>Binary</th><th>First app frame</th></tr></thead><tbody id="candidates"></tbody></table></div></section>
        <footer>Generated from __CAPTURE__. Simulator faults reflect macOS host VM/cache behavior and are not physical-device storage measurements.</footer></main>
        <script>__PLOTLY__</script><script>
        const D=__DATA__,E={i:0,t:1,a:2,h:3,m:4,o:5,th:6,tid:7,f:8,b:9,app:10,d:11,s:12},BLUE="#2563eb",ORANGE="#f59e0b";
        const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
        const stack=e=>D.stacks[e[E.s]].map(i=>D.frames[i]),fmt=n=>new Intl.NumberFormat().format(n);let shown=[],selected=null;
        function header(){const s=D.stats,m=D.metadata,c=s.class_counts||{};document.getElementById("subtitle").textContent=`@@{m.target_kind} · @@{m.target_name} · PID @@{m.target_pid} · @@{Number(s.capture_span_ms).toFixed(1)} ms`;
        document.getElementById("cards").innerHTML=[["Faults",s.event_count],["Minor",c.Minor||0],["Major",c.Major||0],["Major stacks",`@@{s.major_faults_with_stack||0} / @@{c.Major||0}`],["Page size",s.page_size_bytes?`@@{s.page_size_bytes/1024} KiB`:"—"]].map(x=>`<div class=card><strong>@@{typeof x[1]==="number"?fmt(x[1]):x[1]}</strong><span>@@{x[0]}</span></div>`).join("")}
        function filter(){const mi=document.getElementById("minor").checked,ma=document.getElementById("major").checked,q=document.getElementById("search").value.toLowerCase();shown=D.events.filter(e=>(e[E.m]?ma:mi)&&(!q||[e[E.o],e[E.th],e[E.f],e[E.b],...stack(e)].join(" ").toLowerCase().includes(q)));document.getElementById("visible").textContent=`@@{fmt(shown.length)} faults`}
        function address(){const minor=shown.filter(e=>!e[E.m]),major=shown.filter(e=>e[E.m]),trace=(rows,name,color,symbol)=>({type:"scatter",mode:"markers",name,x:rows.map(e=>e[E.t]),y:rows.map(e=>Number(e[E.a])),customdata:rows.map(e=>[e[E.h],e[E.o],e[E.f],e[E.i]]),marker:{color,symbol,size:symbol==="diamond"?8:5,opacity:symbol==="diamond"?.95:.45},hovertemplate:`<b>@@{name}</b> · %{customdata[1]}<br>%{x:.3f} ms · %{customdata[0]}<br>%{customdata[2]}<extra></extra>`});
        Plotly.react("address",[trace(minor,"Minor",BLUE,"circle"),trace(major,"Major",ORANGE,"diamond")],{margin:{l:110,r:20,t:25,b:55},paper_bgcolor:"#fff",plot_bgcolor:"#fff",legend:{orientation:"h"},xaxis:{title:"Time since first fault (ms)",gridcolor:"#e2e8f0"},yaxis:{title:"Virtual fault address",gridcolor:"#e2e8f0",tickformat:".3e"}},{responsive:true,displaylogo:false,scrollZoom:true})}
        function draw(){const c=document.getElementById("stack"),r=c.getBoundingClientRect(),d=Math.min(devicePixelRatio||1,2);c.width=Math.max(400,r.width*d);c.height=Math.max(300,r.height*d);const x=c.getContext("2d"),p={l:55*d,r:15*d,t:15*d,b:35*d},w=c.width-p.l-p.r,h=c.height-p.t-p.b,max=Math.max(1,...shown.map(e=>stack(e).length)),rh=Math.min(13*d,h/max),step=w/Math.max(1,shown.length),bw=Math.max(d,Math.min(10*d,step));x.fillStyle="#fff";x.fillRect(0,0,c.width,c.height);
        shown.forEach((e,i)=>{const ids=D.stacks[e[E.s]].slice().reverse(),px=p.l+i*step;ids.forEach((id,j)=>{x.fillStyle=`hsl(@@{(id*137.5)%360} 55% 50%)`;x.globalAlpha=.8;x.fillRect(px,p.t+h-(j+1)*rh,bw,Math.max(1,rh-.5*d))});x.globalAlpha=1;x.fillStyle=e[E.m]?ORANGE:BLUE;x.fillRect(px,p.t,Math.max(d,bw),2*d)});x.fillStyle="#65718a";x.font=`@@{12*d}px sans-serif`;x.textAlign="right";x.fillText("root",p.l-7*d,p.t+h-rh/2);x.fillText("leaf",p.l-7*d,p.t+rh);c._chart={p,w,step}}
        function list(){const node=document.getElementById("list");node.innerHTML=shown.map((e,i)=>`<button class="row @@{selected===e?"selected":""}" data-i="@@{i}"><span class="pill @@{e[E.m]?"major":"minor"}">@@{e[E.m]?"Major":"Minor"}</span><span class=mono>#@@{e[E.i]} · @@{e[E.t].toFixed(3)} ms · @@{esc(e[E.h])}</span><span class=frame>@@{esc(e[E.f]||"(unresolved)")} · @@{esc(e[E.b])}</span></button>`).join("");node.querySelectorAll("button").forEach(b=>b.onclick=()=>select(shown[Number(b.dataset.i)]))}
        function select(e){selected=e;const labels=stack(e).slice().reverse();document.getElementById("detail").innerHTML=`<h3>Fault #@@{e[E.i]} · @@{e[E.m]?"Major":"Minor"}</h3><p><b>@@{esc(e[E.o])}</b> at @@{e[E.t].toFixed(6)} ms<br><span class=mono>@@{esc(e[E.h])}</span><br>@@{esc(e[E.f]||"unresolved")} · @@{esc(e[E.b]||"—")}<br>Thread: @@{esc(e[E.th]||"—")} @@{e[E.tid]?"("+e[E.tid]+")":""}</p><h3>Complete stack · root → faulting frame</h3>@@{labels.length?`<ol class=stack>@@{labels.map(x=>`<li>@@{esc(x)}</li>`).join("")}</ol>`:"<p>No stack captured.</p>"}`;list()}
        function candidates(){document.getElementById("candidates").innerHTML=D.majorSummary.map(r=>`<tr><td>@@{r.major_fault_count}</td><td>@@{Number(r.first_fault_ms).toFixed(3)} ms</td><td>@@{esc(r.faulting_frame)}</td><td>@@{esc(r.faulting_binary)}</td><td>@@{esc(r.first_app_frame||"—")}</td></tr>`).join("")||"<tr><td colspan=5>No verified app-owned candidates.</td></tr>"}
        function update(){filter();address();draw();list();if(!shown.length){selected=null;document.getElementById("detail").innerHTML="<p>No faults match the active filters.</p>";return}if(!selected||!shown.includes(selected))select(shown.find(e=>e[E.m])||shown[0])}document.getElementById("stack").onclick=e=>{const c=e.currentTarget,q=c._chart;if(!q||!shown.length)return;const ratio=Math.max(0,Math.min(.999999,(e.offsetX-55)/(c.clientWidth-70)));select(shown[Math.floor(ratio*shown.length)])};
        document.getElementById("minor").onchange=update;document.getElementById("major").onchange=update;document.getElementById("search").oninput=update;addEventListener("resize",draw);header();candidates();update();
        </script></body></html>
        """.trimIndent().replace("@@{", "${'$'}{")
}
