package io.pyroscope.javaagent;

import io.pyroscope.http.Format;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.labels.Pyroscope;
import io.pyroscope.labels.io.pyroscope.PyroscopeAsyncProfiler;
import one.profiler.AsyncProfiler;
import one.profiler.Counter;
import one.jfr.JfrReader;
import io.pyroscope.javaagent.pprof.jfr2pprof;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public final class Profiler {
    private Config config;
    private EventType eventType;
    private String alloc;
    private String lock;
    private Duration interval;
    private Format format;
    private File tempJFRFile = null;

    private final AsyncProfiler instance = PyroscopeAsyncProfiler.getAsyncProfiler();

    Profiler(final Config config) {
        setConfig(config);

        if (asyncProfilerJfr()) {
            try {
                // flight recorder is built on top of a file descriptor, so we need a file.
                tempJFRFile = File.createTempFile("pyroscope", ".jfr");
                tempJFRFile.deleteOnExit();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private boolean asyncProfilerJfr() {
        return Format.JFR == format || Format.PPROF == format;
    }

    public void setConfig(final Config config) {
        this.config = config;
        this.alloc = config.profilingAlloc;
        this.lock = config.profilingLock;
        this.eventType = config.profilingEvent;
        this.interval = config.profilingInterval;
        this.format = config.format;
    }

    /**
     * Start async-profiler
     */
    public synchronized void start() {
        if (asyncProfilerJfr()) {
            try {
                instance.execute(createJFRCommand());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        } else {
            instance.start(eventType.id, interval.toNanos());
        }
    }

    /**
     * Stop async-profiler
     */
    public synchronized void stop() {
        instance.stop();
    }

    /**
     *
     * @param started - time when profiling has been started
     * @param ended - time when profiling has ended
     * @return Profiling data and dynamic labels as {@link Snapshot}
     */
    public synchronized Snapshot dumpProfile(Instant started, Instant ended) {
        return dumpImpl(started, ended);
    }

    private String createJFRCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("start,event=").append(eventType.id);
        if (alloc != null && !alloc.isEmpty()) {
            sb.append(",alloc=").append(alloc);
            if (config.allocLive) {
                sb.append(",live");
            }
        }
        if (lock != null && !lock.isEmpty()) {
            sb.append(",lock=").append(lock);
        }
        sb.append(",interval=").append(interval.toNanos())
            .append(",file=").append(tempJFRFile.toString());
        if (config.APLogLevel != null) {
            sb.append(",loglevel=").append(config.APLogLevel);
        }
        sb.append(",jstackdepth=").append(config.javaStackDepthMax);
        if (config.APExtraArguments != null) {
            sb.append(",").append(config.APExtraArguments);
        }
        return sb.toString();
    }

    private Snapshot dumpImpl(Instant started, Instant ended) {
        if (config.gcBeforeDump) {
            System.gc();
        }
        final byte[] data;
        if (Format.JFR == format) {
            data = dumpJFR();
        } else if (Format.PPROF == format) {
            data = dumpPprof();
        } else {
            data = instance.dumpCollapsed(Counter.SAMPLES).getBytes(StandardCharsets.UTF_8);
        }
        return new Snapshot(
            format,
            eventType,
            started,
            ended,
            data,
            Pyroscope.LabelsWrapper.dump()
        );
    }

    private byte[] dumpPprof() {
        try (final JfrReader jfrReader = new JfrReader(tempJFRFile.getAbsolutePath());
                final ByteArrayOutputStream out = new ByteArrayOutputStream();) {
            jfr2pprof.Convert(jfrReader, out, eventType.id);
            return out.toByteArray();
        } catch (final Throwable e) {
            throw new IllegalStateException(e);
        }
    }
    
    private byte[] dumpJFR() {
        try {
            byte[] bytes = new byte[(int) tempJFRFile.length()];
            try (DataInputStream ds = new DataInputStream(new FileInputStream(tempJFRFile))) {
                ds.readFully(bytes);
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
