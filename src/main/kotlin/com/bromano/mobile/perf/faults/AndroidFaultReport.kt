package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal fun stableAndroidSourceLabel(
    path: String,
    packageName: String,
): String {
    val components = path.split('/').filter(String::isNotBlank)
    val packageIndex =
        components.indexOfFirst { component ->
            component == packageName || component.startsWith("$packageName-")
        }
    return components
        .takeIf { packageIndex >= 0 }
        ?.drop(packageIndex + 1)
        ?.joinToString("/")
        ?.ifBlank { Path.of(path).name }
        ?: path
}

internal class AndroidFaultReport(
    private val engineRoot: Path,
) {
    private val comparisonProvenance =
        listOf(
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

    fun build(
        capture: Path,
        output: Path,
        label: String,
        comparison: Path? = null,
        comparisonLabel: String = "Comparison",
        allowIncomparable: Boolean = false,
    ) {
        val primary = load(capture, label)
        val secondary = comparison?.let { load(it, comparisonLabel) }
        val mismatches =
            secondary
                ?.let {
                    comparisonProvenance.filter {
                        val left = primary.metadata[it]
                        val right = secondary.metadata[it]
                        left == null || left.toString().isBlank() || right == null || right.toString().isBlank() || left != right
                    }
                }.orEmpty()
        if (secondary != null && !allowIncomparable) {
            require(mismatches.isEmpty()) {
                "Captures are not directly comparable; mismatched provenance: ${mismatches.joinToString()}"
            }
        }
        val plotly =
            Files.readString(
                engineRoot.resolve("ios/ios_fault_visualizer/assets/plotly.min.js"),
            )
        Files.createDirectories(output.parent)
        Files.writeString(output, html(primary, secondary, mismatches, plotly))
    }

    private data class Capture(
        val label: String,
        val path: Path,
        val metadata: Map<String, Any?>,
        val allFaults: List<Map<String, String>>,
        val mappedFaults: List<Map<String, String>>,
        val pageCache: List<Map<String, String>>,
        val residency: List<Map<String, String>>,
        val boundaries: List<Map<String, String>>,
        val callchains: List<Map<String, String>>,
    )

    private fun load(
        path: Path,
        label: String,
    ): Capture {
        val metadataPath = path.resolve("capture_metadata.json")
        require(Files.isRegularFile(metadataPath)) {
            "$path is not an mperf Android fault capture"
        }
        val metadata = Json.readMap(metadataPath)
        require((metadata["schema_version"] as? Number)?.toInt() == 5) {
            "Unsupported Android capture schema in $path"
        }
        require(metadata["capture_status"] == "collected") {
            "$path is incomplete: capture_status=${metadata["capture_status"]}"
        }
        require(metadata["processing_status"] == "complete") {
            "$path has not completed preprocessing: processing_status=${metadata["processing_status"]}"
        }
        listOf("all_faults.csv", "mapped_faults.csv", "page_cache_events.csv").forEach { name ->
            require(Files.isRegularFile(path.resolve(name))) {
                "$path is missing required processed artifact $name"
            }
        }
        val integrityKeys = listOf("lost", "integrity_errors", "throttled", "callchain_overflow")
        val failures =
            integrityKeys.associateWith { key ->
                (metadata["collector_$key"] as? Number)?.toLong() ?: 0L
            }
        require(((metadata["collector_return_code"] as? Number)?.toInt() ?: 0) == 0 && failures.values.all { it == 0L }) {
            "$path failed collector integrity checks: $failures"
        }
        return Capture(
            label = label,
            path = path,
            metadata = metadata,
            allFaults = Csv.read(path.resolve("all_faults.csv")),
            mappedFaults = Csv.read(path.resolve("mapped_faults.csv")),
            pageCache = Csv.read(path.resolve("page_cache_events.csv")),
            residency = Csv.read(path.resolve("cache_residency.csv")),
            boundaries = Csv.read(path.resolve("vdex_dex_boundaries.csv")),
            callchains = Csv.read(path.resolve("resolved_fault_callchains.csv")),
        )
    }

    private fun sourceLabel(
        row: Map<String, String>,
        packageName: String,
    ): String {
        val file = row["file_name"].orEmpty()
        val entry = row["zip_entry_name"].orEmpty()
        val base = fileLabel(file, packageName)
        return if (entry.isBlank() || file.endsWith(".vdex", ignoreCase = true)) base else "$base › $entry"
    }

    private fun fileLabel(
        file: String,
        packageName: String,
    ): String = if (isAppOwned(file, packageName)) stableAndroidSourceLabel(file, packageName) else file

    private fun isAppOwned(
        path: String,
        packageName: String,
    ): Boolean {
        val escaped = Regex.escape(packageName)
        return Regex("(?:^|/)$escaped(?:/|-[^/]+(?:/|$)|$)").containsMatchIn(path)
    }

    private fun html(
        capture: Capture,
        comparison: Capture?,
        comparisonMismatches: List<String>,
        plotly: String,
    ): String {
        val packageName = capture.metadata["package"].toString()
        val all =
            capture.allFaults.map { row ->
                mapOf(
                    "sequence" to row.long("sequence"),
                    "time" to row.double("elapsed_ms"),
                    "address" to row.long("address"),
                    "addressHex" to "0x${row.long("address").toString(16)}",
                    "major" to row.boolean("is_major"),
                    "mapping" to row["mapping_kind"].orEmpty(),
                    "file" to row["file_name"].orEmpty(),
                    "offset" to row.longOrNull("offset"),
                    "thread" to row["thread_name"].orEmpty(),
                )
            }
        val mapped =
            capture.mappedFaults.map { row ->
                mapOf(
                    "sequence" to row.long("sequence"),
                    "time" to row.double("elapsed_ms"),
                    "major" to row.boolean("is_major"),
                    "source" to sourceLabel(row, packageName),
                    "fileLabel" to fileLabel(row["file_name"].orEmpty(), packageName),
                    "file" to row["file_name"].orEmpty(),
                    "fullPath" to row["file_name"].orEmpty(),
                    "page" to row.doubleOrNull("page_index"),
                    "sectionPage" to row.doubleOrNull("section_page"),
                    "entry" to row["zip_entry_name"].orEmpty(),
                    "category" to row["category"].orEmpty(),
                    "thread" to row["thread_name"].orEmpty(),
                )
            }
        val cacheRows =
            capture.pageCache.map { row ->
                mapOf(
                    "time" to row.double("elapsed_ms"),
                    "page" to row.doubleOrNull("page_index"),
                    "source" to sourceLabel(row, packageName),
                    "thread" to row["thread_name"].orEmpty(),
                    "process" to row["process_name"].orEmpty(),
                )
            }
        val chains =
            capture.callchains
                .filter { it["frame_kind"] == "user" }
                .map { row ->
                    mapOf(
                        "sequence" to row.long("sequence"),
                        "index" to row.long("frame_index"),
                        "major" to row.boolean("is_major"),
                        "label" to row["label"].orEmpty(),
                        "file" to row["file_name"].orEmpty(),
                        "ip" to "0x${row.unsignedLong("ip").toString(16)}",
                        "kind" to callchainKind(row["file_name"].orEmpty(), packageName),
                    )
                }
        val boundaries =
            capture.boundaries.map { row ->
                mapOf(
                    "file" to row["file_name"].orEmpty(),
                    "name" to row["dex_name"].orEmpty(),
                    "page" to row.doubleOrNull("page_index"),
                )
            }
        val compareData =
            comparison?.let { other ->
                val otherPackage = other.metadata["package"].toString()
                other.mappedFaults.map { row ->
                    mapOf(
                        "major" to row.boolean("is_major"),
                        "source" to sourceLabel(row, otherPackage),
                    )
                }
            }
        val model =
            mapOf(
                "metadata" to capture.metadata,
                "label" to capture.label,
                "all" to all,
                "mapped" to mapped,
                "cache" to cacheRows,
                "chains" to chains,
                "boundaries" to boundaries,
                "comparison" to compareData,
                "comparisonLabel" to comparison?.label,
            )
        val json = Json.mapper.writeValueAsString(model).replace("<", "\\u003c")
        val major = mapped.count { it["major"] == true }
        val minor = mapped.size - major
        val resolvedFrames =
            (capture.metadata["callchain_results"] as? Map<*, *>)
                ?.get("resolved_user_frames")
                ?.toString()
                ?.toLongOrNull()
                ?: chains.count { (it["file"] as String).isNotBlank() }.toLong()
        val totalFrames = chains.size.toLong()
        val stackSection =
            if (chains.isEmpty()) {
                ""
            } else {
                """
                <section>
                  <h2>Exact fault-trigger callchains</h2>
                  <p>Instruction-pointer callchains captured in the same perf record as each exact fault. The oldest captured
                  user frame is at the bottom and the faulting frame is at the top. Zoom the fault-order axis to inspect individual stacks.</p>
                  <p class="note">$resolvedFrames of $totalFrames user frames mapped to a timestamp-valid file and offset.
                  Frame-pointer unwinding can omit managed/JIT/interpreter frames and native code without usable frame pointers.</p>
                  <div id="stack" class="chart tall"></div>
                </section>
                """.trimIndent()
            }
        val comparisonSection =
            if (comparison == null) {
                ""
            } else {
                """
                <section>
                  <h2>Capture comparison</h2>
                  <p>Major-fault counts are compared by exact source. Differences can also reflect startup behavior,
                  compilation state, and residual cache state.</p>
                  <div id="comparison" class="chart"></div>
                </section>
                """.trimIndent()
            }
        val cacheVerification = capture.metadata["cache_verification"] as? Map<*, *>
        val cacheResident = (cacheVerification?.get("resident_pages") as? Number)?.toLong()
        val cacheTotal = (cacheVerification?.get("total_pages") as? Number)?.toLong()
        val cacheFiles = (cacheVerification?.get("files_checked") as? Number)?.toLong()
        val cacheLimit = (capture.metadata["cache_max_resident_pages"] as? Number)?.toLong()
        val cachePhase = cacheVerification?.get("phase")?.toString().orEmpty()
        val warnings = (capture.metadata["warnings"] as? List<*>).orEmpty().map(Any?::toString)
        val postLaunchWarning = capture.metadata["post_launch_residency_error"]?.toString()
        val warningItems =
            (warnings + listOfNotNull(postLaunchWarning?.let { "Post-launch residency check: $it" }))
                .joinToString("") { "<li>${escape(it)}</li>" }
        val residencyPhases =
            capture.residency
                .groupBy { it["phase"].orEmpty() }
                .map { (phase, rows) ->
                    val resident = rows.sumOf { it["resident_pages"].orEmpty().toLongOrNull() ?: 0L }
                    val total = rows.sumOf { it["total_pages"].orEmpty().toLongOrNull() ?: 0L }
                    "$phase: $resident/$total resident pages"
                }.joinToString(" · ")
        val cacheStatus =
            when {
                cacheResident == null -> "No pre-launch residency evidence"
                cacheResident == 0L -> "Verified non-resident"
                else -> "Accepted residual residency"
            }
        val cacheSection =
            """
            <section>
              <h2>Cache verification</h2>
              <div class="status ${if ((cacheResident ?: 1) == 0L) "good" else "warn"}">
                <b>${escape(cacheStatus)}</b> · ${cacheResident ?: "?"}/${cacheTotal ?: "?"} pages resident across
                ${cacheFiles ?: "?"} checked files at ${escape(cachePhase.ifBlank { "the pre-launch gate" })};
                allowed maximum: ${cacheLimit ?: "?"}.
              </div>
              ${if (residencyPhases.isBlank()) "" else "<p class=\"note\">${escape(residencyPhases)}</p>"}
              ${if (warningItems.isBlank()) "" else "<h3>Capture warnings</h3><ul>$warningItems</ul>"}
            </section>
            """.trimIndent()
        val comparisonWarning =
            if (comparisonMismatches.isEmpty()) {
                ""
            } else {
                """
                <section class="warning"><h2>Exploratory comparison</h2>
                <p>These captures differ in required provenance and should not be interpreted as a controlled cohort:
                ${escape(comparisonMismatches.joinToString(", "))}.</p></section>
                """.trimIndent()
            }
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Android startup page-fault report — ${escape(capture.label)}</title>
              <style>
                :root{color-scheme:light;--ink:#172033;--muted:#64748b;--line:#dbe4ee;--bg:#f4f7fb;--minor:#2563eb;--major:#d97706}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 Inter,ui-sans-serif,system-ui,sans-serif}
                main{max-width:1500px;margin:0 auto;padding:28px}header,section{background:white;border:1px solid var(--line);border-radius:18px;padding:28px;margin:0 0 24px;box-shadow:0 8px 30px #3341550a}
                h1{font-size:42px;margin:0 0 8px}h2{font-size:28px;margin:0 0 10px}p{max-width:1100px}.eyebrow{color:var(--minor);font-weight:800;letter-spacing:.12em;text-transform:uppercase}
                .subtitle,.note{color:var(--muted)}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-top:22px}.metric{border:1px solid var(--line);border-radius:14px;padding:16px}.metric b{font-size:30px;display:block}
                .chart{width:100%;height:680px}.chart.tall{height:780px}label{font-weight:700}select{max-width:900px;width:100%;padding:10px;margin:8px 0 14px;border:1px solid #b9c7d8;border-radius:9px;background:white}
                .status{border-left:5px solid var(--line);padding:12px 16px;background:#f8fafc}.status.good{border-color:#16a34a}.status.warn,.warning{border-color:#d97706}
                .table-wrap{overflow:auto}table{width:100%;border-collapse:collapse}th,td{padding:9px 12px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}th:first-child,td:first-child{text-align:left}
                @media(max-width:800px){main{padding:12px}.metrics{grid-template-columns:1fr 1fr}h1{font-size:32px}}
              </style>
              <script>$plotly</script>
            </head>
            <body><main>
              <header>
                <div class="eyebrow">Android fault visualizer</div>
                <h1>Startup page-fault report</h1>
                <div class="subtitle">${escape(packageName)} · Android ${escape(capture.metadata["release"].toString())}
                (API ${escape(capture.metadata["sdk"].toString())}) · ${escape(capture.label)}</div>
                <div class="metrics">
                  <div class="metric"><span>File-backed major</span><b>$major</b></div>
                  <div class="metric"><span>File-backed minor</span><b>$minor</b></div>
                  <div class="metric"><span>Recorded faults</span><b>${all.size}</b></div>
                  <div class="metric"><span>Attributed files</span><b>${mapped.map { it["file"] }.toSet().size}</b></div>
                </div>
              </header>
              $cacheSection
              $comparisonWarning
              <section><h2>Every recorded fault</h2><p>Fault time versus virtual address across file-backed,
              anonymous, and unresolved mappings. Circles are minor; diamonds are major.</p><div id="all" class="chart"></div></section>
              $stackSection
              <section><h2>Where startup faults came from</h2><p>Sources are ordered by major faults, then total faults.
              Hover for the complete path and counts.</p><div id="sources" class="chart"></div></section>
              <section><h2>When each file entered the working set</h2><p>Every file remains a separate lane; no aggregate
              “Other” bucket is used.</p><div id="timeline" class="chart tall"></div></section>
              <section><h2>How sequential the page pattern was</h2><p>Page index in fault order. Diagonal bands are
              sequential; vertical jumps reach distant pages. A VDEX is one analytical source and verified embedded
              DEX starts are marked in red.</p><label for="source">File or verified section</label><select id="source"></select>
              <div id="sequence" class="chart"></div><div id="opportunities" class="table-wrap"></div></section>
              <section><h2>Page-cache insertions during startup</h2><p>These are correlated cache fills, including
              background kernel workers targeting the app’s exact device/inode pairs. They are not faults and do not
              prove which insertion served a later access.</p><div id="cache" class="chart"></div></section>
              $comparisonSection
            </main>
            <script>
              const M=$json, blue='#2563eb', orange='#d97706', grid='#dbe4ee';
              const layout=(extra={})=>Object.assign({paper_bgcolor:'white',plot_bgcolor:'white',font:{color:'#172033'},
                margin:{l:85,r:30,t:35,b:75},hovermode:'closest',xaxis:{gridcolor:grid},yaxis:{gridcolor:grid}},extra);
              const config={responsive:true,displaylogo:false,scrollZoom:true};
              function split(rows){return [rows.filter(x=>!x.major),rows.filter(x=>x.major)]}
              let [amin,amaj]=split(M.all);
              Plotly.newPlot('all',[
                {x:amin.map(x=>x.time),y:amin.map(x=>x.address),text:amin.map(x=>`${'$'}{x.addressHex}<br>${'$'}{x.file||x.mapping}<br>${'$'}{x.thread}`),type:'scatter',mode:'markers',name:'Minor',marker:{size:4,color:blue},hovertemplate:'%{text}<br>%{x:.2f} ms<extra></extra>'},
                {x:amaj.map(x=>x.time),y:amaj.map(x=>x.address),text:amaj.map(x=>`${'$'}{x.addressHex}<br>${'$'}{x.file||x.mapping}<br>${'$'}{x.thread}`),type:'scatter',mode:'markers',name:'Major',marker:{size:7,color:orange,symbol:'diamond'},hovertemplate:'%{text}<br>%{x:.2f} ms<extra></extra>'}
              ],layout({xaxis:{title:'Elapsed startup time (ms)',gridcolor:grid},yaxis:{title:'Virtual fault address',gridcolor:grid,tickformat:'.3s'}}),config);
              const summary=new Map(); for(const x of M.mapped){let s=summary.get(x.source)||{source:x.source,file:x.fullPath,major:0,minor:0};x.major?s.major++:s.minor++;summary.set(x.source,s)}
              const ranked=[...summary.values()].sort((a,b)=>b.major-a.major||(b.major+b.minor)-(a.major+a.minor));
              Plotly.newPlot('sources',[
                {y:ranked.map(x=>x.source),x:ranked.map(x=>x.major),customdata:ranked.map(x=>x.file),type:'bar',orientation:'h',name:'Major',marker:{color:orange},hovertemplate:'%{customdata}<br>Major %{x}<extra></extra>'},
                {y:ranked.map(x=>x.source),x:ranked.map(x=>x.minor),customdata:ranked.map(x=>x.file),type:'bar',orientation:'h',name:'Minor',marker:{color:blue},hovertemplate:'%{customdata}<br>Minor %{x}<extra></extra>'}
              ],layout({barmode:'group',height:Math.max(620,ranked.length*25),xaxis:{title:'Faults',gridcolor:grid},yaxis:{autorange:'reversed',automargin:true,tickfont:{size:11}}}),config);
              const lanes=[...new Set(M.mapped.map(x=>x.source))]; const lane=new Map(lanes.map((x,i)=>[x,i]));
              let [tmin,tmaj]=split(M.mapped);
              Plotly.newPlot('timeline',[
                {x:tmin.map(x=>x.time),y:tmin.map(x=>lane.get(x.source)),text:tmin.map(x=>`${'$'}{x.fullPath}<br>page ${'$'}{x.page}<br>${'$'}{x.thread}`),type:'scatter',mode:'markers',name:'Minor',marker:{size:5,color:blue},hovertemplate:'%{text}<br>%{x:.2f} ms<extra></extra>'},
                {x:tmaj.map(x=>x.time),y:tmaj.map(x=>lane.get(x.source)),text:tmaj.map(x=>`${'$'}{x.fullPath}<br>page ${'$'}{x.page}<br>${'$'}{x.thread}`),type:'scatter',mode:'markers',name:'Major',marker:{size:7,color:orange,symbol:'x'},hovertemplate:'%{text}<br>%{x:.2f} ms<extra></extra>'}
              ],layout({height:Math.max(760,lanes.length*21),xaxis:{title:'Elapsed startup time (ms)',gridcolor:grid},yaxis:{tickmode:'array',tickvals:lanes.map((_,i)=>i),ticktext:lanes,autorange:'reversed',automargin:true,tickfont:{size:10}}}),config);
              const views=[];
              for(const f of new Set(M.mapped.map(x=>x.file))){
                const rows=M.mapped.filter(x=>x.file===f&&x.page!==null);
                if(rows.length>=3)views.push({key:`file:${'$'}{f}`,file:f,label:`${'$'}{rows[0].fileLabel}${'$'}{f.toLowerCase().endsWith('.vdex')?' · entire file':''}`,rows,page:'page',whole:true});
                if(!f.toLowerCase().endsWith('.vdex')){for(const entry of new Set(rows.map(x=>x.entry).filter(Boolean))){const section=rows.filter(x=>x.entry===entry&&x.sectionPage!==null);
                  if(section.length>=3)views.push({key:`section:${'$'}{f}:${'$'}{entry}`,file:f,label:`${'$'}{rows[0].fileLabel} › ${'$'}{entry}`,rows:section,page:'sectionPage',whole:false})}}
              }
              views.sort((a,b)=>{const rank=x=>x.whole&&x.file.toLowerCase().endsWith('base.vdex')?0:x.whole&&x.file.toLowerCase().endsWith('.vdex')?1:x.whole&&x.file.toLowerCase().endsWith('base.odex')?2:x.whole?4:3;return rank(a)-rank(b)||b.rows.length-a.rows.length||a.label.localeCompare(b.label)});
              const select=document.getElementById('source'); for(const v of views){const o=document.createElement('option');o.value=v.key;o.textContent=v.label;select.appendChild(o)}
              function metrics(v){const rows=v.rows.slice().sort((a,b)=>a.sequence-b.sequence),pages=rows.map(x=>x[v.page]),jumps=pages.slice(1).map((x,i)=>Math.abs(x-pages[i])).sort((a,b)=>a-b);
                return {source:v.label,faults:rows.length,major:rows.filter(x=>x.major).length,unique:new Set(pages).size,next:jumps.length?100*jumps.filter(x=>x===1).length/jumps.length:0,median:jumps.length?jumps[Math.floor(jumps.length/2)]:0}}
              const opportunityRows=views.map(metrics).sort((a,b)=>b.major-a.major||b.faults-a.faults).slice(0,50);
              document.getElementById('opportunities').innerHTML='<table><thead><tr><th>File or section</th><th>Major</th><th>All faults</th><th>Unique pages</th><th>Next-page steps</th><th>Median jump</th></tr></thead><tbody>'+
                opportunityRows.map(x=>`<tr><td>${'$'}{x.source.replaceAll('&','&amp;').replaceAll('<','&lt;')}</td><td>${'$'}{x.major}</td><td>${'$'}{x.faults}</td><td>${'$'}{x.unique}</td><td>${'$'}{x.next.toFixed(1)}%</td><td>${'$'}{x.median} pages</td></tr>`).join('')+'</tbody></table>';
              function drawSequence(){const v=views.find(x=>x.key===select.value),rows=v.rows.slice().sort((a,b)=>a.sequence-b.sequence).map((x,i)=>Object.assign({fileOrder:i},x)),parts=split(rows);
                const traces=[{x:parts[0].map(x=>x.fileOrder),y:parts[0].map(x=>x[v.page]),text:parts[0].map(x=>`${'$'}{x.fullPath}<br>${'$'}{x.entry||''}`),type:'scatter',mode:'markers',name:'Minor',marker:{size:6,color:blue}},
                  {x:parts[1].map(x=>x.fileOrder),y:parts[1].map(x=>x[v.page]),text:parts[1].map(x=>`${'$'}{x.fullPath}<br>${'$'}{x.entry||''}`),type:'scatter',mode:'markers',name:'Major',marker:{size:8,color:orange,symbol:'x'}}];
                const shapes=[],annotations=[]; if(v.whole){for(const b of M.boundaries.filter(x=>x.file===v.file)){shapes.push({type:'line',x0:0,x1:1,xref:'paper',y0:b.page,y1:b.page,line:{color:'#dc2626',width:1.5}});annotations.push({x:1,xref:'paper',y:b.page,text:b.name,showarrow:false,xanchor:'right',font:{color:'#dc2626'}})}}
                Plotly.react('sequence',traces,layout({xaxis:{title:'Fault order within selected view',gridcolor:grid},yaxis:{title:`${'$'}{v.whole?'File':'Section'} page index (${'$'}{M.metadata.page_size/1024} KiB pages)`,gridcolor:grid},shapes,annotations}),config)}
              select.addEventListener('change',drawSequence); if(views.length)drawSequence();
              Plotly.newPlot('cache',[{x:M.cache.map(x=>x.time),y:M.cache.map(x=>x.page),text:M.cache.map(x=>`${'$'}{x.source}<br>${'$'}{x.process} · ${'$'}{x.thread}`),type:'scatter',mode:'markers',marker:{size:6,color:'#7c3aed'},hovertemplate:'%{text}<br>page %{y}<br>%{x:.2f} ms<extra></extra>'}],
                layout({xaxis:{title:'Elapsed startup time (ms)',gridcolor:grid},yaxis:{title:'File page index',gridcolor:grid}}),config);
              if(M.chains.length){const groups=new Map();for(const f of M.chains){if(!groups.has(f.sequence))groups.set(f.sequence,[]);groups.get(f.sequence).push(f)}
                const seq=[...groups.keys()].sort((a,b)=>a-b),ordered=seq.map(s=>groups.get(s).sort((a,b)=>b.index-a.index)),depth=Math.max(...ordered.map(x=>x.length)),z=Array.from({length:depth},()=>Array(seq.length).fill(null)),custom=Array.from({length:depth},()=>Array(seq.length).fill(null));
                ordered.forEach((frames,c)=>frames.forEach((f,d)=>{z[d][c]=f.kind;custom[d][c]=[f.label,f.file||'unresolved',f.ip]}));
                Plotly.newPlot('stack',[{x:seq,y:Array.from({length:depth},(_,i)=>i),z,customdata:custom,type:'heatmap',zmin:0,zmax:4,colorscale:[[0,'#16a34a'],[.24,'#16a34a'],[.25,'#7c3aed'],[.49,'#7c3aed'],[.5,'#2563eb'],[.74,'#2563eb'],[.75,'#64748b'],[.99,'#64748b'],[1,'#94a3b8']],colorbar:{title:'Frame',tickvals:[0,1,2,3,4],ticktext:['App','ART/JIT','OAT/JAR','System','Unknown']},hovertemplate:'Fault #%{x}<br>Depth %{y}<br><b>%{customdata[0]}</b><br>%{customdata[1]}<br>%{customdata[2]}<extra></extra>'}],
                  layout({xaxis:{title:'Fault order',rangeslider:{visible:true,thickness:.07},gridcolor:grid},yaxis:{title:'Frame depth (oldest captured → faulting frame)',gridcolor:grid}}),config)}
              if(M.comparison){const summarize=rows=>{const m=new Map();for(const x of rows){if(x.major)m.set(x.source,(m.get(x.source)||0)+1)}return m},a=summarize(M.mapped),b=summarize(M.comparison),keys=[...new Set([...a.keys(),...b.keys()])].sort((x,y)=>(b.get(y)||0)-(b.get(x)||0)||(a.get(y)||0)-(a.get(x)||0));
                Plotly.newPlot('comparison',[{x:keys,y:keys.map(x=>a.get(x)||0),type:'bar',name:M.label,marker:{color:blue}},{x:keys,y:keys.map(x=>b.get(x)||0),type:'bar',name:M.comparisonLabel,marker:{color:orange}}],
                  layout({barmode:'group',xaxis:{tickangle:-35,automargin:true},yaxis:{title:'Major faults',gridcolor:grid}}),config)}
            </script></body></html>
            """.trimIndent()
    }

    private fun callchainKind(
        path: String,
        packageName: String,
    ): Int =
        when {
            isAppOwned(path, packageName) -> 0
            path.contains("libart.so") || path.contains("dalvik-jit-code-cache") -> 1
            path.endsWith(".oat") ||
                path.endsWith(".odex") ||
                path.endsWith(".vdex") ||
                path.endsWith(".jar") ||
                path.endsWith(".art") -> 2
            path.startsWith("/system/") ||
                path.startsWith("/apex/") ||
                path.startsWith("/vendor/") ||
                path.startsWith("/product/") -> 3
            else -> 4
        }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun Map<String, String>.long(key: String): Long =
        get(key).orEmpty().let { value ->
            if (value.startsWith("0x")) value.substring(2).toLong(16) else value.toLong()
        }

    private fun Map<String, String>.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isNotBlank() }?.let { value ->
            if (value.startsWith("0x")) value.substring(2).toLong(16) else value.toLong()
        }

    private fun Map<String, String>.unsignedLong(key: String): ULong =
        getValue(key).let { value ->
            if (value.startsWith("0x")) value.substring(2).toULong(16) else value.toULong()
        }

    private fun Map<String, String>.double(key: String): Double = getValue(key).toDouble()

    private fun Map<String, String>.doubleOrNull(key: String): Double? = get(key)?.takeIf { it.isNotBlank() }?.toDouble()

    private fun Map<String, String>.boolean(key: String): Boolean = get(key).equals("true", ignoreCase = true)
}
