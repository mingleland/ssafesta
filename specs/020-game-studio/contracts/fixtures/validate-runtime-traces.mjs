import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { applyRuntimeInput, snapshotRuntimeState, startRuntime } from "./reference-runtime.mjs";

const fixtureDir = dirname(fileURLToPath(import.meta.url));
const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));

const assertSubset = (actual, expected, path = "state") => {
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual) || JSON.stringify(actual) !== JSON.stringify(expected)) {
      throw new Error(`${path}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
    return;
  }
  if (expected !== null && typeof expected === "object") {
    for (const [key, value] of Object.entries(expected)) assertSubset(actual?.[key], value, `${path}.${key}`);
    return;
  }
  if (actual !== expected) throw new Error(`${path}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
};

const tracePath = join(fixtureDir, "runtime-traces/minimal-top-down-dialogue.trace.json");
const trace = readJson(tracePath);
const project = readJson(join(dirname(tracePath), trace.project));
let state;

for (const [index, step] of trace.steps.entries()) {
  if (step.input.type === "START") state = startRuntime(project);
  else applyRuntimeInput(project, state, step.input);
  assertSubset(snapshotRuntimeState(state), step.expect, `step[${index}]`);
  console.log(`PASS runtime step ${index + 1} ${step.input.type}`);
}

console.log(`GameProject runtime trace: ${trace.steps.length}/${trace.steps.length} passed`);
