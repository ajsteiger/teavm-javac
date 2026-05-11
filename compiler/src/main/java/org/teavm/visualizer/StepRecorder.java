/*
 *  Copyright 2026 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.visualizer;

import java.util.IdentityHashMap;

/**
 * <p>Runtime recorder used by the pure-browser Java visualizer.</p>
 *
 * <p>This class is compiled <em>alongside</em> user code inside the {@code compiler.wasm}
 * pipeline. Each static method is wired to a matching JavaScript callback via
 * {@code @JSBody} (or the equivalent Wasm GC import). The JS callback is defined
 * in {@code worker.js} and routes events into the trace array rendered by
 * {@code visualizer.js}.</p>
 *
 * <p>NOTE: This source file is also published as a resource so that the teavm-javac
 * integration can inject it as an additional source file when {@code generateVisualizer()}
 * is invoked on the {@code Compiler} object exposed by {@code compiler.wasm}.</p>
 *
 * <p>A step-limit guard ({@link #MAX_STEPS}) prevents infinite loops from exhausting
 * browser memory. When the limit is reached the trace is marked as truncated and
 * no further steps are recorded.</p>
 *
 * <p>A bounded call-stack of up to {@link #MAX_DEPTH} frames is maintained so that
 * each step event carries the full stack trace in the VIZ protocol output.</p>
 */
public final class StepRecorder {
    /** Maximum number of steps that will be recorded before truncation. */
    public static final int MAX_STEPS = 10_000;

    /** Maximum call-stack depth tracked by the frame arrays. */
    private static final int MAX_DEPTH = 64;

    private static final String[] frameClass = new String[MAX_DEPTH];
    private static final String[] frameMeth = new String[MAX_DEPTH];
    private static final int[] frameLine = new int[MAX_DEPTH];

    private static int depth;
    private static int stepCount;
    private static boolean truncated;
    private static final IdentityHashMap<Object, Integer> objectIds = new IdentityHashMap<>();
    private static int nextObjectId = 1;

    private StepRecorder() {
    }

    /**
     * Called by instrumented code at the start of each distinct source line.
     *
     * @param className  binary class name of the enclosing class
     * @param methodName simple name of the enclosing method
     * @param line       1-based source-line number
     */
    public static void step(String className, String methodName, int line) {
        if (truncated) {
            return;
        }
        if (stepCount >= MAX_STEPS) {
            truncated = true;
            jsStepLimitReached();
            return;
        }
        stepCount++;
        if (depth > 0) {
            frameLine[depth - 1] = line;
        }
        jsStep(className, methodName, line);
    }

    /**
     * Called by instrumented code on method entry.
     *
     * @param className  binary class name
     * @param methodName simple method name
     */
    public static void enterMethod(String className, String methodName) {
        if (!truncated) {
            if (depth < MAX_DEPTH) {
                frameClass[depth] = className;
                frameMeth[depth] = methodName;
                frameLine[depth] = 0;
                depth++;
            }
            jsEnterMethod(className, methodName);
        }
    }

    /**
     * Called by instrumented code on method exit (before every {@code return}).
     */
    public static void exitMethod() {
        if (!truncated) {
            jsExitMethod();
            if (depth > 0) {
                depth--;
            }
        }
    }

    /**
     * Called by instrumented code to capture a named local variable at the current step.
     * The value is converted to a string; reference types show their
     * {@link Object#toString()} representation.
     *
     * @param name  Java variable name (from the local-variable table)
     * @param value current value (may be {@code null})
     */
    public static void captureVar(String name, Object value) {
        if (!truncated) {
            jsCaptureVar(name, value == null ? "null" : value.toString());
        }
    }

    /**
     * Captures a named local variable that holds a reference type at the current step.
     *
     * <p>For {@code null}, emits {@code "null"}.  For primitives wrapped as objects,
     * emits their {@link Object#toString()} value.  For other objects, emits
     * {@code "@id:TypeName{field=val,...}"} using runtime reflection to walk the
     * object's declared instance fields.  Nested object field values are encoded as
     * {@code "@id"} (no recursive expansion, avoiding cycles).</p>
     *
     * @param name  Java variable name (from the local-variable table)
     * @param value current value (may be {@code null})
     */
    public static void captureRef(String name, Object value) {
        if (truncated) {
            return;
        }
        if (value == null) {
            jsCaptureVar(name, "null");
            return;
        }
        if (value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            jsCaptureVar(name, value.toString());
            return;
        }
        if (value instanceof Object[]) {
            jsCaptureVar(name, objectArrayToString((Object[]) value));
            return;
        }
        int id = getObjectId(value);
        Class<?> cls = value.getClass();
        StringBuilder sb = new StringBuilder("@").append(Integer.toUnsignedString(id))
                .append(":").append(cls.getSimpleName()).append("{");
        boolean first = true;
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object fval;
            try {
                fval = f.get(value);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(f.getName()).append("=");
            if (fval == null) {
                sb.append("null");
            } else if (fval instanceof String || fval instanceof Number
                    || fval instanceof Boolean || fval instanceof Character) {
                sb.append(fval);
            } else {
                sb.append("@").append(Integer.toUnsignedString(getObjectId(fval)));
            }
        }
        sb.append("}");
        jsCaptureVar(name, sb.toString());
    }

    private static String objectArrayToString(Object[] value) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object item = value[i];
            if (item == null) {
                sb.append("null");
            } else if (item instanceof String || item instanceof Number
                    || item instanceof Boolean || item instanceof Character) {
                sb.append(item);
            } else {
                sb.append("@").append(Integer.toUnsignedString(getObjectId(item)));
            }
        }
        return sb.append("]").toString();
    }

    /**
     * Captures an {@code int} (or {@code byte}/{@code short}) named variable.
     *
     * @param name  Java variable name
     * @param value current value
     */
    public static void captureVar(String name, int value) {
        if (!truncated) {
            jsCaptureVar(name, String.valueOf(value));
        }
    }

    /**
     * Captures a {@code long} named variable.
     *
     * @param name  Java variable name
     * @param value current value
     */
    public static void captureVar(String name, long value) {
        if (!truncated) {
            jsCaptureVar(name, String.valueOf(value));
        }
    }

    /**
     * Captures a {@code float} named variable.
     *
     * @param name  Java variable name
     * @param value current value
     */
    public static void captureVar(String name, float value) {
        if (!truncated) {
            jsCaptureVar(name, String.valueOf(value));
        }
    }

    /**
     * Captures a {@code double} named variable.
     *
     * @param name  Java variable name
     * @param value current value
     */
    public static void captureVar(String name, double value) {
        if (!truncated) {
            jsCaptureVar(name, String.valueOf(value));
        }
    }

    /**
     * Captures a {@code boolean} named variable.
     *
     * @param name  Java variable name
     * @param value current value
     */
    public static void captureVar(String name, boolean value) {
        if (!truncated) {
            jsCaptureVar(name, String.valueOf(value));
        }
    }

    /**
     * Captures a {@code char} named variable.
     *
     * @param name  Java variable name
     * @param value current value
     */
    public static void captureVar(String name, char value) {
        if (!truncated) {
            jsCaptureVar(name, String.valueOf(value));
        }
    }

    /** Returns whether the step limit has been exceeded. */
    public static boolean isTruncated() {
        return truncated;
    }

    /** Returns the number of steps recorded so far. */
    public static int getStepCount() {
        return stepCount;
    }

    /**
     * Resets all recorder state to its initial (empty) values.
     * Intended for use in unit tests; not meant to be called from instrumented
     * production code.
     */
    static void reset() {
        stepCount = 0;
        truncated = false;
        depth = 0;
        objectIds.clear();
        nextObjectId = 1;
    }

    private static int getObjectId(Object value) {
        var id = objectIds.get(value);
        if (id == null) {
            id = nextObjectId++;
            objectIds.put(value, id);
        }
        return id;
    }

    // -----------------------------------------------------------------
    // JS-interop stubs (replaced by @JSBody / Wasm-GC import at build
    // time; provided here as implementations that emit the VIZ protocol
    // to stderr so the class is self-contained when running inside the
    // teavm-javac pipeline which reads stderr for trace events).
    // -----------------------------------------------------------------

    static void jsStep(String className, String methodName, int line) {
        StringBuilder sb = new StringBuilder("\u0000VIZ:step");
        for (int i = 0; i < depth; i++) {
            sb.append(':').append(frameClass[i])
              .append(':').append(frameMeth[i])
              .append(':').append(frameLine[i]);
        }
        System.err.println(sb.toString());
    }

    static void jsEnterMethod(String className, String methodName) {
        // no-op: frame push is performed in enterMethod() before this call
    }

    static void jsExitMethod() {
        // no-op: frame pop is performed in exitMethod() after this call
    }

    static void jsCaptureVar(String name, String value) {
        System.err.println("\u0000VIZ:var:" + name + "=" + value);
    }

    static void jsStepLimitReached() {
        System.err.println("\u0000VIZ:truncated");
    }
}
