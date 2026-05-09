import fs from "node:fs";
import { load } from "./compiler/build/generated/teavm/wasm-gc/compiler.wasm-runtime.js";

const compilerWasm = new Int8Array(fs.readFileSync("./compiler/build/generated/teavm/wasm-gc/compiler.wasm"));
const sdk = new Int8Array(fs.readFileSync("./compiler/build/classlib/compile-classlib-teavm.bin"));
const runtimeClasslib = new Int8Array(fs.readFileSync("./compiler/build/classlib/runtime-classlib-teavm.bin"));

const source = `class Node {
    int value;
    Node next;

    Node(int value, Node next) {
        this.value = value;
        this.next = next;
    }
}

public class LinkedListDemo {
    public static void main(String[] args) {
        Node n1 = new Node(99, null);
        Node n2 = new Node(17, n1);
        Node n3 = new Node(42, n2);
        Node head = n3;
        int sum = 0;
        Node current = head;
        while (current != null) {
            sum = sum + current.value;
            current = current.next;
        }
        System.out.println("Sum = " + sum);
    }
}`;

const compilerRuntime = await load(compilerWasm);
const compiler = compilerRuntime.exports.createCompiler();
const diagnostics = [];
compiler.onDiagnostic((diagnostic) => diagnostics.push(diagnostic));
compiler.setSdk(sdk);
compiler.setTeaVMClasslib(runtimeClasslib);
compiler.addSourceFile("LinkedListDemo.java", source);

const ok = compiler.generateVisualizer({ outputName: "app", mainClass: "LinkedListDemo" });
if (!ok) {
    console.error("generateVisualizer failed");
    for (const diagnostic of diagnostics) {
        console.error(diagnostic.severity, diagnostic.fileName, diagnostic.lineNumber, diagnostic.message);
    }
    process.exit(1);
}

const appWasm = compiler.getWebAssemblyOutputFile("app.wasm");
if (!appWasm) {
    console.error("app.wasm was not generated");
    process.exit(1);
}

const vizLines = [];
let stdout = "";
let stdoutLine = "";
let stderrLine = "";
const app = await load(appWasm, {
    installImports(imports) {
        imports.teavmConsole.putcharStdout = (ch) => {
            if (ch === 10) {
                stdout += stdoutLine + "\n";
                stdoutLine = "";
            } else {
                stdoutLine += String.fromCharCode(ch);
            }
        };
        imports.teavmConsole.putcharStderr = (ch) => {
            if (ch === 10) {
                if (stderrLine.startsWith("\u0000VIZ:")) {
                    vizLines.push(stderrLine.slice("\u0000VIZ:".length));
                }
                stderrLine = "";
            } else {
                stderrLine += String.fromCharCode(ch);
            }
        };
    },
});
await app.exports.main([]);

const hasNodeObject = vizLines.some((line) => /^var:\w+=@\d+:Node\{/.test(line));
const hasNodePointer = vizLines.some((line) => /^var:\w+=@\d+:Node\{.*next=@\d+/.test(line));
if (!hasNodeObject || !hasNodePointer || !stdout.includes("Sum = 158")) {
    console.error("Unexpected visualizer output");
    console.error({ hasNodeObject, hasNodePointer, stdout, vizLines: vizLines.slice(0, 20) });
    process.exit(1);
}

console.log("generateVisualizer smoke passed");
