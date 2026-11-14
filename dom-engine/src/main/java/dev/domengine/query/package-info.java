/**
 * Syntax layer of the Control Engine.
 * Files here may use the CDP exception type, plus image, json, log, and tree.
 * Only QueryHooks may use the pre-map and affinity packages.
 * The live connection type stays in LiveXPath and the xpath method of Engines.
 * Displayed and RowStates already take that connection for engine-defects D6 and D10.
 * Session, action, and desktop packages, and browser drivers, are not imported here.
 */
package dev.domengine.query;
