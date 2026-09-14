import { BLOCK_TYPE, createCoreRegistry } from "../src/packs/core";
import { CONTROL_TYPE, STACK_TYPE, STATEMENT_TYPE, createTypedRegistry } from "../src/packs/typed";
import { createPickleballRegistry } from "../src/packs/pickleball";

export { BLOCK_TYPE, CONTROL_TYPE, STACK_TYPE, STATEMENT_TYPE };

export const core = createCoreRegistry();
export const typed = createTypedRegistry();
export const pickleball = createPickleballRegistry();
