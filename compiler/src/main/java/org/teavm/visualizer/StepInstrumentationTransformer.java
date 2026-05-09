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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.teavm.common.GraphUtils;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Incoming;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Phi;
import org.teavm.model.Program;
import org.teavm.model.TextLocation;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.AssignInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.StringConstantInstruction;
import org.teavm.model.util.DefinitionExtractor;
import org.teavm.model.util.ProgramUtils;
import org.teavm.model.util.TypeInferer;
import org.teavm.model.util.VariableType;

/**
 * <p>A {@link ClassHolderTransformer} that instruments user code with calls to
 * {@link StepRecorder} so that a pure-browser Java visualizer (PythonTutor-style)
 * can replay execution step by step.</p>
 *
 * <h2>What is inserted</h2>
 * <ul>
 *   <li><strong>Method entry</strong> – a call to
 *       {@code StepRecorder.enterMethod(className, methodName)} is prepended to
 *       the first basic block of every instrumented method.</li>
 *   <li><strong>Source-line steps</strong> – a call to
 *       {@code StepRecorder.step(className, methodName, line)} is inserted
 *       immediately before the first instruction that carries a new source-line
 *       number. Inlined call sites are ignored (only top-level locations are
 *       tracked).</li>
 *   <li><strong>Method exit</strong> – a call to
 *       {@code StepRecorder.exitMethod()} is inserted immediately before every
 *       {@link ExitInstruction} (i.e., before every {@code return}).</li>
 * </ul>
 *
 * <h2>Which classes are instrumented</h2>
 * <p>Only classes whose binary name starts with the {@code targetClassPrefix}
 * supplied to the constructor are instrumented. The {@link StepRecorder} class
 * itself is never instrumented (it would cause infinite recursion).</p>
 *
 * <h2>Integration with teavm-javac</h2>
 * <p>This transformer is intended to be installed in the
 * {@code generateVisualizer()} method of the {@code Compiler} object exposed by
 * {@code compiler.wasm}:</p>
 * <pre>{@code
 * teavm.add(new StepInstrumentationTransformer(mainClass));
 * }</pre>
 * <p>The {@link StepRecorder} class must be reachable from the compiled output
 * (either via the TeaVM classlib archive or by adding its source to the javac
 * compilation step).</p>
 */
public class StepInstrumentationTransformer implements ClassHolderTransformer {
    private static final String RECORDER_CLASS = "org.teavm.visualizer.StepRecorder";
    private static boolean visualizerReflectionEnabled;

    private final String targetClassPrefix;

    /**
     * Creates a new transformer.
     *
     * @param targetClassPrefix binary class-name prefix of classes to instrument
     *                          (e.g. the user's main class name, or a package prefix
     *                          like {@code "com.example."})
     */
    public StepInstrumentationTransformer(String targetClassPrefix) {
        this.targetClassPrefix = targetClassPrefix;
        visualizerReflectionEnabled = true;
    }

    static boolean isVisualizerReflectionEnabled() {
        return visualizerReflectionEnabled;
    }

    static void disableVisualizerReflectionForTests() {
        visualizerReflectionEnabled = false;
    }

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        String name = cls.getName();
        if (!name.startsWith(targetClassPrefix) || name.equals(RECORDER_CLASS)) {
            return;
        }
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram();
            if (program != null && program.basicBlockCount() > 0 && hasSourceInfo(program)) {
                instrumentMethod(name, method);
            }
        }
    }

    // ----------------------------------------------------------------
    //  Instrumentation logic
    // ----------------------------------------------------------------

    private void instrumentMethod(String className, MethodHolder method) {
        Program program = method.getProgram();
        String methodName = method.getName();

        // Infer types for all SSA variables so we can pick the right captureVar overload.
        // Guard against programs with fewer variables than the method signature expects
        // (e.g. synthetic / test programs that omit the implicit variable-0 slot).
        TypeInferer typeInferer = new TypeInferer();
        try {
            typeInferer.inferTypes(program, method.getReference());
        } catch (ArrayIndexOutOfBoundsException ignored) {
            typeInferer = null;
        }

        // Pre-pass: propagate debug names through phi edges and assignment copies to
        // recover names that Parser.appendDebugNames may have missed (e.g. loop-variable
        // phi receivers where PhiUpdater failed to record the source-variable mapping).
        propagateDebugNames(program);

        // Parameter variables: SSA registers 1..paramCount are always live everywhere.
        Map<String, Variable> paramVars = new LinkedHashMap<>();
        Map<String, ValueType> paramTypes = new LinkedHashMap<>();
        int slot = 1;
        for (ValueType paramType : method.getParameterTypes()) {
            // Walk variables to find the one whose register == slot and has a debug name.
            for (int vi = 0; vi < program.variableCount(); vi++) {
                Variable v = program.variableAt(vi);
                if (v.getRegister() == slot && v.getDebugName() != null) {
                    paramVars.put(v.getDebugName(), v);
                    paramTypes.put(v.getDebugName(), paramType);
                    break;
                }
            }
            slot++;
        }

        // Build per-block "inherited phi vars": named phi receivers from the dominator chain.
        // This makes loop-body blocks inherit the loop-header's phi variables so they are
        // visible at each step even though those blocks have no phis of their own.
        Map<String, Variable>[] inheritedPhiVars = buildInheritedPhiVars(program);

        // Insert enterMethod() at the very start of the method.
        BasicBlock entry = program.basicBlockAt(0);
        Instruction firstInsn = entry.getFirstInstruction();
        if (firstInsn != null) {
            firstInsn.insertPreviousAll(buildEnterMethodCall(program, className, methodName));
        }

        DefinitionExtractor defExtractor = new DefinitionExtractor();
        TextLocation lastLoc = null;
        for (int bi = 0; bi < program.basicBlockCount(); bi++) {
            BasicBlock block = program.basicBlockAt(bi);
            if (block == null) {
                continue;
            }

            // At block entry: start with vars inherited from the dominator chain (covers
            // loop-header phis becoming visible in the loop body), then add this block's
            // own phis on top (they shadow inherited vars with the same name).
            Map<String, Variable> phiVars = new LinkedHashMap<>(
                    inheritedPhiVars[bi] != null ? inheritedPhiVars[bi] : Map.of());
            for (Phi phi : block.getPhis()) {
                Variable receiver = phi.getReceiver();
                String name = receiver.getDebugName();
                if (name != null) {
                    phiVars.put(name, receiver);
                }
            }

            // Track variables defined earlier in this block (before the current step).
            Map<String, Variable> blockLocalVars = new LinkedHashMap<>();

            Instruction insn = block.getFirstInstruction();
            while (insn != null) {
                Instruction next = insn.getNext();

                if (insn instanceof ExitInstruction) {
                    insn.insertPreviousAll(buildExitMethodCall(program));
                } else {
                    TextLocation loc = insn.getLocation();
                    if (loc != null && loc.getLine() > 0
                            && loc.getInlining() == null
                            && !loc.equals(lastLoc)) {
                        lastLoc = loc;
                        insn.insertPreviousAll(buildStepCall(program, className, methodName, loc.getLine()));

                        // Emit captureVar for visible named variables:
                        //   params (always) < phis (loop merges) < block-locals (same-block prior assigns)
                        // Later entries shadow earlier ones with the same name, so the most
                        // specific (most recently defined) value is emitted.
                        Map<String, Variable> visible = new LinkedHashMap<>(paramVars);
                        Map<String, ValueType> knownTypes = new LinkedHashMap<>(paramTypes);
                        visible.putAll(phiVars);
                        for (String name : phiVars.keySet()) {
                            knownTypes.remove(name);
                        }
                        visible.putAll(blockLocalVars);
                        for (String name : blockLocalVars.keySet()) {
                            knownTypes.remove(name);
                        }
                        for (Map.Entry<String, Variable> e : visible.entrySet()) {
                            Variable v = e.getValue();
                            VariableType vt = typeInferer != null ? typeInferer.typeOf(v.getIndex()) : null;
                            ValueType valueType = knownTypes.get(e.getKey());
                            if (valueType == null) {
                                valueType = variableTypeToValueType(vt);
                            }
                            insn.insertPreviousAll(
                                    buildCaptureVarCall(program, e.getKey(), v, valueType));
                        }
                    }

                    // Record any named variable defined by this instruction for subsequent steps.
                    insn.acceptVisitor(defExtractor);
                    for (Variable defined : defExtractor.getDefinedVariables()) {
                        String name = defined.getDebugName();
                        if (name != null) {
                            blockLocalVars.put(name, defined);
                        }
                    }
                }

                insn = next;
            }
        }
    }

    /**
     * Propagates debug names through phi incoming edges and {@link AssignInstruction} copies
     * until no more changes occur. This fixes cases where {@link org.teavm.parsing.Parser}
     * could not assign debug names because {@link org.teavm.model.util.PhiUpdater} did not
     * record the source-variable mapping for synthesised phi receivers.
     *
     * <p>Propagation rules (applied in each iteration until convergence):</p>
     * <ul>
     *   <li><b>Forward phi:</b> if any incoming has a name and all named incomings agree,
     *       give that name to the phi receiver.</li>
     *   <li><b>Reverse phi:</b> if a phi receiver has a name, propagate it to every
     *       nameless incoming (covering the loop-body assignment variable).</li>
     *   <li><b>AssignInstruction (both directions):</b> copy the debug name between
     *       assignee and receiver when one is named and the other is not.</li>
     * </ul>
     */
    private static void propagateDebugNames(Program program) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int bi = 0; bi < program.basicBlockCount(); bi++) {
                BasicBlock block = program.basicBlockAt(bi);
                if (block == null) {
                    continue;
                }

                for (Phi phi : block.getPhis()) {
                    Variable receiver = phi.getReceiver();

                    // Forward: incomings → receiver
                    if (receiver.getDebugName() == null) {
                        String name = consensusIncomingName(phi);
                        if (name != null) {
                            receiver.setDebugName(name);
                            changed = true;
                        }
                    }

                    // Reverse: receiver → nameless incomings
                    String name = receiver.getDebugName();
                    if (name != null) {
                        for (Incoming incoming : phi.getIncomings()) {
                            if (incoming.getValue().getDebugName() == null) {
                                incoming.getValue().setDebugName(name);
                                changed = true;
                            }
                        }
                    }
                }

                // AssignInstruction: bidirectional copy
                for (Instruction insn : block) {
                    if (insn instanceof AssignInstruction) {
                        AssignInstruction assign = (AssignInstruction) insn;
                        String assigneeName = assign.getAssignee().getDebugName();
                        String receiverName = assign.getReceiver().getDebugName();
                        if (assigneeName != null && receiverName == null) {
                            assign.getReceiver().setDebugName(assigneeName);
                            changed = true;
                        } else if (receiverName != null && assigneeName == null) {
                            assign.getAssignee().setDebugName(receiverName);
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the single debug name shared by all named incoming variables of {@code phi},
     * or {@code null} if there are no named incomings or the named incomings disagree.
     */
    private static String consensusIncomingName(Phi phi) {
        String name = null;
        for (Incoming incoming : phi.getIncomings()) {
            String incomingName = incoming.getValue().getDebugName();
            if (incomingName != null) {
                if (name == null) {
                    name = incomingName;
                } else if (!name.equals(incomingName)) {
                    return null;
                }
            }
        }
        return name;
    }

    /**
     * Builds a per-block array of "inherited named phi vars": for each block, this map
     * contains all named phi receivers from every block that strictly dominates it.
     *
     * <p>This is used so that a loop-body block (which has no phis of its own) can still
     * capture the loop-header's phi variables at each step.</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Variable>[] buildInheritedPhiVars(Program program) {
        int count = program.basicBlockCount();
        Map<String, Variable>[] result = new Map[count];
        result[0] = Map.of();

        var domGraph = GraphUtils.buildDominatorGraph(
                GraphUtils.buildDominatorTree(ProgramUtils.buildControlFlowGraph(program)), count);

        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int bi = queue.poll();
            BasicBlock block = program.basicBlockAt(bi);
            // exitVars = inherited vars + this block's own named phis
            Map<String, Variable> exitVars = new LinkedHashMap<>(result[bi]);
            for (Phi phi : block.getPhis()) {
                Variable v = phi.getReceiver();
                if (v.getDebugName() != null) {
                    exitVars.put(v.getDebugName(), v);
                }
            }
            for (int child : domGraph.outgoingEdges(bi)) {
                if (result[child] == null) {
                    result[child] = exitVars;
                    queue.add(child);
                }
            }
        }

        // Fill any unreachable blocks
        for (int i = 0; i < count; i++) {
            if (result[i] == null) {
                result[i] = Map.of();
            }
        }
        return result;
    }

    // ----------------------------------------------------------------
    //  Instruction builders
    // ----------------------------------------------------------------

    /**
     * Builds: {@code StepRecorder.enterMethod(className, methodName);}
     */
    private List<Instruction> buildEnterMethodCall(Program program, String className, String methodName) {
        List<Instruction> result = new ArrayList<>();
        Variable classNameVar = loadString(program, className, result);
        Variable methodNameVar = loadString(program, methodName, result);

        InvokeInstruction invoke = new InvokeInstruction();
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(new MethodReference(RECORDER_CLASS, "enterMethod",
                ValueType.object("java.lang.String"),
                ValueType.object("java.lang.String"),
                ValueType.VOID));
        invoke.setArguments(classNameVar, methodNameVar);
        result.add(invoke);
        return result;
    }

    /**
     * Builds: {@code StepRecorder.step(className, methodName, line);}
     */
    private List<Instruction> buildStepCall(Program program, String className, String methodName, int line) {
        List<Instruction> result = new ArrayList<>();
        Variable classNameVar = loadString(program, className, result);
        Variable methodNameVar = loadString(program, methodName, result);
        Variable lineVar = loadInt(program, line, result);

        InvokeInstruction invoke = new InvokeInstruction();
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(new MethodReference(RECORDER_CLASS, "step",
                ValueType.object("java.lang.String"),
                ValueType.object("java.lang.String"),
                ValueType.INTEGER,
                ValueType.VOID));
        invoke.setArguments(classNameVar, methodNameVar, lineVar);
        result.add(invoke);
        return result;
    }

    /**
     * Builds: {@code StepRecorder.exitMethod();}
     */
    private List<Instruction> buildExitMethodCall(Program program) {
        List<Instruction> result = new ArrayList<>();

        InvokeInstruction invoke = new InvokeInstruction();
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(new MethodReference(RECORDER_CLASS, "exitMethod", ValueType.VOID));
        result.add(invoke);
        return result;
    }

    /**
     * Builds: {@code StepRecorder.captureVar(name, value);}
     * Selects the typed overload appropriate for {@code type}.
     */
    private List<Instruction> buildCaptureVarCall(Program program, String varName,
            Variable paramVar, ValueType type) {
        List<Instruction> result = new ArrayList<>();
        Variable nameVar = loadString(program, varName, result);

        InvokeInstruction invoke = new InvokeInstruction();
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(captureVarMethodRef(type));
        invoke.setArguments(nameVar, paramVar);
        result.add(invoke);
        return result;
    }

    /**
     * Returns the {@link MethodReference} for the appropriate {@code StepRecorder} capture
     * method for the given parameter type.  Primitives and their wrappers use the typed
     * {@code captureVar} overloads; reference types use {@code captureRef} which emits the
     * {@code @id:TypeName{fields}} format understood by the heap panel.
     */
    private static MethodReference captureVarMethodRef(ValueType type) {
        if (type == ValueType.LONG) {
            return new MethodReference(RECORDER_CLASS, "captureVar",
                    ValueType.object("java.lang.String"), ValueType.LONG, ValueType.VOID);
        } else if (type == ValueType.FLOAT) {
            return new MethodReference(RECORDER_CLASS, "captureVar",
                    ValueType.object("java.lang.String"), ValueType.FLOAT, ValueType.VOID);
        } else if (type == ValueType.DOUBLE) {
            return new MethodReference(RECORDER_CLASS, "captureVar",
                    ValueType.object("java.lang.String"), ValueType.DOUBLE, ValueType.VOID);
        } else if (type == ValueType.BOOLEAN) {
            return new MethodReference(RECORDER_CLASS, "captureVar",
                    ValueType.object("java.lang.String"), ValueType.BOOLEAN, ValueType.VOID);
        } else if (type == ValueType.CHARACTER) {
            return new MethodReference(RECORDER_CLASS, "captureVar",
                    ValueType.object("java.lang.String"), ValueType.CHARACTER, ValueType.VOID);
        } else if (type instanceof ValueType.Primitive) {
            // BYTE, SHORT, INTEGER all map to the int overload
            return new MethodReference(RECORDER_CLASS, "captureVar",
                    ValueType.object("java.lang.String"), ValueType.INTEGER, ValueType.VOID);
        } else {
            // Reference type: use captureRef to emit @id:TypeName{fields} for heap visualization
            return new MethodReference(RECORDER_CLASS, "captureRef",
                    ValueType.object("java.lang.String"), ValueType.object("java.lang.Object"), ValueType.VOID);
        }
    }

    /**
     * Maps a {@link VariableType} (from {@link TypeInferer}) to the {@link ValueType}
     * expected by the {@link #captureVarMethodRef(ValueType)} overload selector.
     */
    private static ValueType variableTypeToValueType(VariableType type) {
        if (type == null) {
            return ValueType.object("java.lang.Object");
        }
        switch (type) {
            case INT:    return ValueType.INTEGER;
            case LONG:   return ValueType.LONG;
            case FLOAT:  return ValueType.FLOAT;
            case DOUBLE: return ValueType.DOUBLE;
            default:     return ValueType.object("java.lang.Object");
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    /** Emits a {@link StringConstantInstruction} and returns the receiver variable. */
    private Variable loadString(Program program, String value, List<Instruction> out) {
        Variable v = program.createVariable();
        StringConstantInstruction insn = new StringConstantInstruction();
        insn.setConstant(value);
        insn.setReceiver(v);
        out.add(insn);
        return v;
    }

    /** Emits an {@link IntegerConstantInstruction} and returns the receiver variable. */
    private Variable loadInt(Program program, int value, List<Instruction> out) {
        Variable v = program.createVariable();
        IntegerConstantInstruction insn = new IntegerConstantInstruction();
        insn.setConstant(value);
        insn.setReceiver(v);
        out.add(insn);
        return v;
    }

    /** Returns {@code true} if any instruction in the program carries a source location. */
    private boolean hasSourceInfo(Program program) {
        for (int bi = 0; bi < program.basicBlockCount(); bi++) {
            BasicBlock block = program.basicBlockAt(bi);
            if (block == null) {
                continue;
            }
            for (Instruction insn = block.getFirstInstruction(); insn != null; insn = insn.getNext()) {
                TextLocation loc = insn.getLocation();
                if (loc != null && loc.getLine() > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
