package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;

public final class BinaryStateConditions {

    private BinaryStateConditions() {
        // utility class
    }

    public static XPathy onElement() {
        String base = new XPathy().getXpath(); // "//*"

        // Any self-or-descendant with a "truthy" binary attribute.
        // Presence counts as true unless explicitly "false" or "0".
        String pred =
                "descendant-or-self::*[" +
                        "(" +
                        "(@checked and not(@checked='false' or @checked='0')) or " +
                        "(@selected and not(@selected='false' or @selected='0')) or " +
                        "(@aria-checked and not(@aria-checked='false' or @aria-checked='0')) or " +
                        "(@aria-selected and not(@aria-selected='false' or @aria-selected='0')) or " +
                        "(@aria-pressed and not(@aria-pressed='false' or @aria-pressed='0')) or " +
                        "(@data-checked and not(@data-checked='false' or @data-checked='0')) or " +
                        "(@data-selected and not(@data-selected='false' or @data-selected='0'))" +
                        ")" +
                        "]";

        return new XPathy(base + "[" + pred + "]");
    }

    public static XPathy offElement() {
        String base = new XPathy().getXpath(); // "//*"

        // OFF = not(ON)
        String onPred =
                "descendant-or-self::*[" +
                        "(" +
                        "(@checked and not(@checked='false' or @checked='0')) or " +
                        "(@selected and not(@selected='false' or @selected='0')) or " +
                        "(@aria-checked and not(@aria-checked='false' or @aria-checked='0')) or " +
                        "(@aria-selected and not(@aria-selected='false' or @aria-selected='0')) or " +
                        "(@aria-pressed and not(@aria-pressed='false' or @aria-pressed='0')) or " +
                        "(@data-checked and not(@data-checked='false' or @data-checked='0')) or " +
                        "(@data-selected and not(@data-selected='false' or @data-selected='0'))" +
                        ")" +
                        "]";

        return new XPathy(base + "[not(" + onPred + ")]");
    }
}
