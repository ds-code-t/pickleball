package tools.dscode.common.composite;

import tools.dscode.common.domoperations.elementstates.BinaryStateConditions;
import tools.dscode.common.domoperations.elementstates.BlankElementConditions;
import tools.dscode.common.domoperations.elementstates.CollapsedExpandedConditions;
import tools.dscode.common.domoperations.elementstates.EnabledDisabledConditions;
import tools.dscode.common.domoperations.elementstates.RequiredInputConditions;

import java.util.Map;

/**
 * The XPath predicates the element-state classes already use, evaluated on the
 * element the snapshot or describe script already holds. One function, both
 * producers. A driver without {@code document.evaluate} returns null so the
 * caller can fall back. That fallback is the only extra find.
 */
public final class ElementStateScript {
    public static final String FUNCTION = """
            function pickleballElementState(el) {
              if (!el || !el.ownerDocument || typeof el.ownerDocument.evaluate !== 'function') return null;
              function hit(xpath) {
                try {
                  var found = el.ownerDocument.evaluate(xpath, el, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                  var i;
                  for (i = 0; i < found.snapshotLength; i++) {
                    if (found.snapshotItem(i) === el) return true;
                  }
                  return false;
                } catch (ignore) {
                  return null;
                }
              }
              var disabled = hit(%s);
              var blank = hit(%s);
              var required = hit(%s);
              var expanded = hit(%s);
              var attributeOn = hit(%s);
              if (disabled === null || blank === null || required === null || expanded === null || attributeOn === null) return null;
              var tag = String(el.tagName || '').toLowerCase();
              var type = '';
              try { type = String(el.getAttribute('type') || ''); } catch (ignoreType) { type = ''; }
              var selectable = tag === 'option' || (tag === 'input' && (type.toLowerCase() === 'checkbox' || type.toLowerCase() === 'radio'));
              var domSelected = false;
              try { domSelected = !!(el.selected || el.checked); } catch (ignoreSel) { domSelected = false; }
              return {
                disabled: disabled,
                blank: blank,
                required: required,
                expanded: expanded,
                attributeOn: attributeOn,
                selectable: selectable,
                domSelected: domSelected
              };
            }
            """.formatted(
            js(EnabledDisabledConditions.disabledSelfXPath()),
            js(BlankElementConditions.blankSelfXPath()),
            js(RequiredInputConditions.requiredSelfXPath()),
            js(CollapsedExpandedConditions.expandedSelfXPath()),
            js(BinaryStateConditions.onSelfXPath())
    );

    private ElementStateScript() {
    }

    public static ElementState parse(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Boolean disabled = bool(map.get("disabled"));
        Boolean blank = bool(map.get("blank"));
        Boolean required = bool(map.get("required"));
        Boolean expanded = bool(map.get("expanded"));
        Boolean attributeOn = bool(map.get("attributeOn"));
        Boolean selectable = bool(map.get("selectable"));
        Boolean domSelected = bool(map.get("domSelected"));
        if (disabled == null || blank == null || required == null || expanded == null
                || attributeOn == null || selectable == null || domSelected == null) {
            return null;
        }
        return new ElementState(disabled, blank, required, expanded, attributeOn, selectable, domSelected);
    }

    private static Boolean bool(Object raw) {
        if (raw instanceof Boolean value) return value;
        if (raw == null) return null;
        String text = String.valueOf(raw);
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        return null;
    }

    private static String js(String xpath) {
        return "\"" + xpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
