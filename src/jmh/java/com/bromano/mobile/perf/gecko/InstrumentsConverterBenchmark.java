package com.bromano.mobile.perf.gecko;

import com.bromano.mobile.perf.utils.ZipUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
public class InstrumentsConverterBenchmark {
    private Path workspace;
    private Path trace;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        workspace = Files.createTempDirectory("mperf-instruments-benchmark");
        trace = workspace.resolve("example.trace");
        Path archive = workspace.resolve("example.trace.zip");
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/example.trace.zip"))) {
            Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
        }
        ZipUtils.INSTANCE.unzipInstruments(archive, trace);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (workspace == null) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Benchmark
    public void convertSavedTrace(Blackhole blackhole) {
        blackhole.consume(InstrumentsConverter.INSTANCE.convert("perftestexample", trace, 1, null));
    }
}
