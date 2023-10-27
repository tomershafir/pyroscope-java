/*
 * Copyright 2022 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pyroscope.javaagent.pprof;

import one.jfr.ClassRef;
import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.MethodRef;
import one.jfr.StackTrace;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;
import one.proto.Proto;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * References: 
 * <ul><li><a href="https://github.com/async-profiler/async-profiler/pull/713">...</a></li>
 * <li><a href="https://github.com/grafana/JPProf/pull/17">...</a></li></ul>
 * <p>
 */
public class jfr2pprof {
    private jfr2pprof(){}
    public static class Method {
        final byte[] name;

        public Method(final byte[] name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(name);
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Method && Arrays.equals(name, ((Method) other).name);
        }
    }

    public static final String TYPE_CPU = "cpu";
    public static final String TYPE_ALLOC = "alloc";
    public static final String TYPE_LOCK = "lock";
    public static final String TYPE_ITIMER = "itimer";

    public static final byte[] METHOD_UNKNOWN = "[unknown]".getBytes();

    public static final byte[] VALUE_TYPE_CPU = "cpu".getBytes(StandardCharsets.UTF_8);
    public static final byte[] VALUE_TYPE_ALLOC = "allocations".getBytes(StandardCharsets.UTF_8);
    public static final byte[] VALUE_TYPE_LOCK = "lock".getBytes(StandardCharsets.UTF_8);
    public static final byte[] VALUE_TYPE_ITIMER = "itimer".getBytes(StandardCharsets.UTF_8);

    public static final byte[] VALUE_UNIT_CPU = "nanoseconds".getBytes(StandardCharsets.UTF_8);
    public static final byte[] VALUE_UNIT_ALLOC = "count".getBytes(StandardCharsets.UTF_8);
    public static final byte[] VALUE_UNIT_LOCK = "nanoseconds".getBytes(StandardCharsets.UTF_8);
    public static final byte[] VALUE_UNIT_ITIMER = "nanoseconds".getBytes(StandardCharsets.UTF_8);

    // Profile IDs
    public static final int PROFILE_SAMPLE_TYPE = 1;
    public static final int PROFILE_SAMPLE = 2;
    public static final int PROFILE_LOCATION = 4;
    public static final int PROFILE_FUNCTION = 5;
    public static final int PROFILE_STRING_TABLE = 6;
    public static final int PROFILE_TIME_NANOS = 9;
    public static final int PROFILE_DURATION_NANOS = 10;
    public static final int PROFILE_PERIOD_TYPE = 11;
    public static final int PROFILE_COMMENT = 13;
    public static final int PROFILE_DEFAULT_SAMPLE_TYPE = 14;

    // ValueType IDs
    public static final int VALUETYPE_TYPE = 1;
    public static final int VALUETYPE_UNIT = 2;

    // Sample IDs
    public static final int SAMPLE_LOCATION_ID = 1;
    public static final int SAMPLE_VALUE = 2;

    // Location IDs
    public static final int LOCATION_ID = 1;
    public static final int LOCATION_LINE = 4;

    // Line IDs
    public static final int LINE_FUNCTION_ID = 1;
    public static final int LINE_LINE = 2;

    // Function IDs
    public static final int FUNCTION_ID = 1;
    public static final int FUNCTION_NAME = 2;

    // `Proto` instances are mutable, careful with reordering
    public static void Convert(final JfrReader reader, final OutputStream out, final String type) throws IOException {
        // Mutable IDs, need to start at 1
        int functionId = 1;
        int locationId = 1;
        int stringId = 1;

        // Used to de-dupe
        final Map<Method, Integer> functions = new HashMap<>();
        final Map<Method, Integer> locations = new HashMap<>();

        byte[] valueType = null;
        byte[] valueUnit = null;
        Class<? extends Event> eventClass = null;

        if (TYPE_CPU.equals(type)) {
            valueType = VALUE_TYPE_CPU;
            valueUnit = VALUE_UNIT_CPU;
            eventClass = ExecutionSample.class;
        } else if (TYPE_ALLOC.equals(type)) {
            valueType = VALUE_TYPE_ALLOC;
            valueUnit = VALUE_UNIT_ALLOC;
            eventClass = AllocationSample.class;
        } else if (TYPE_LOCK.equals(type)) {
            valueType = VALUE_TYPE_LOCK;
            valueUnit = VALUE_UNIT_LOCK;
            eventClass = ContendedLock.class;
        } else if (TYPE_ITIMER.equals(type)) {
            valueType = VALUE_TYPE_ITIMER;
            valueUnit = VALUE_UNIT_ITIMER;
            eventClass = ExecutionSample.class;  
        }

        final Proto profile = new Proto(200_000)
                .field(PROFILE_TIME_NANOS, reader.startNanos)
                .field(PROFILE_DURATION_NANOS, reader.durationNanos())
                .field(PROFILE_DEFAULT_SAMPLE_TYPE, 0L)
                .field(PROFILE_STRING_TABLE, "".getBytes(StandardCharsets.UTF_8)) // "" needs to be index 0
                .field(PROFILE_STRING_TABLE, "async-profiler".getBytes(StandardCharsets.UTF_8))
                .field(PROFILE_COMMENT, stringId++);

        final Proto sampleType = new Proto(100);

        profile.field(PROFILE_STRING_TABLE, valueType);
        sampleType.field(VALUETYPE_TYPE, stringId++);

        profile.field(PROFILE_STRING_TABLE, valueUnit);
        sampleType.field(VALUETYPE_UNIT, stringId++);

        profile.field(PROFILE_SAMPLE_TYPE, sampleType);
        profile.field(PROFILE_PERIOD_TYPE, sampleType);

        final List<? extends Event> samples = reader.readAllEvents(eventClass);
        final Dictionary<StackTrace> stackTraces = reader.stackTraces;
        long previousTime = reader.startTicks; // Mutate this to keep track of time deltas

        // Iterate over samples
        for (final Event jfrSample : samples) {
            final StackTrace stackTrace = stackTraces.get(jfrSample.stackTraceId);
            final long[] methods = stackTrace.methods;
            final byte[] types = stackTrace.types;

            final long nanosSinceLastSample = (jfrSample.time - previousTime) * 1_000_000_000 / reader.ticksPerSec;
            final Proto sample = new Proto(1_000).field(SAMPLE_VALUE, nanosSinceLastSample);

            for (int current = 0; current < methods.length; current++) {
                final byte methodType = types[current];
                final long methodIdentifier = methods[current];
                final byte[] methodName = getMethodName(reader, methodIdentifier, methodType);
                final Method method = new Method(methodName);
                final int line = stackTrace.locations[current] >>> 16;

                final Integer methodId = functions.get(method);
                if (null == methodId) {
                    final int funcId = functionId++;
                    profile.field(PROFILE_STRING_TABLE, methodName);
                    final Proto function = new Proto(16)
                            .field(FUNCTION_ID, funcId)
                            .field(FUNCTION_NAME, stringId++);

                    profile.field(PROFILE_FUNCTION, function);

                    functions.put(method, funcId);
                }

                final Integer locaId = locations.get(method);
                if (null == locaId) {
                    final int locId = locationId++;
                    final Proto locLine = new Proto(16).field(LINE_FUNCTION_ID, functions.get(method));
                    if (line > 0) {
                        locLine.field(LINE_LINE, line);
                    }

                    final Proto location = new Proto(16)
                            .field(LOCATION_ID, locId)
                            .field(LOCATION_LINE, locLine);

                    profile.field(PROFILE_LOCATION, location);

                    locations.put(method, locId);
                }

                sample.field(SAMPLE_LOCATION_ID, locations.get(method));
            }

            profile.field(PROFILE_SAMPLE, sample);

            previousTime = jfrSample.time;
        }

        out.write(profile.buffer(), 0, profile.size());
    }

    private static byte[] getMethodName(final JfrReader reader, final long methodId, final byte methodType) {
        final MethodRef ref = reader.methods.get(methodId);
        if (null == ref) {
            return METHOD_UNKNOWN;
        }

        final ClassRef classRef = reader.classes.get(ref.cls);
        final byte[] className = reader.symbols.get(classRef.name);
        final byte[] methodName = reader.symbols.get(ref.name);

        if ((methodType >= FlameGraph.FRAME_NATIVE && methodType <= FlameGraph.FRAME_KERNEL) || className == null || className.length == 0) {
            // Native method
            return methodName;
        } else {
            // JVM method
            final byte[] fullName = new byte[className.length + 1 + methodName.length];
            System.arraycopy(className, 0, fullName, 0, className.length);
            fullName[className.length] = '.';
            System.arraycopy(methodName, 0, fullName, className.length + 1, methodName.length);
            return fullName;
        }
    }
}
