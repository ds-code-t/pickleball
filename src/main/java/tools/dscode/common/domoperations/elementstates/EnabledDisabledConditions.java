package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;

public final class EnabledDisabledConditions {

    private EnabledDisabledConditions() {
        // utility class
    }

    public static XPathy disabledElement() {
        String base = new XPathy().getXpath(); // "//*"

        String pred =
                "descendant-or-self::*[" +
                        "(" +
                        "(@disabled and not(@disabled='false' or @disabled='0')) or " +
                        "(@aria-disabled and not(@aria-disabled='false' or @aria-disabled='0')) or " +
                        "(@data-disabled and not(@data-disabled='false' or @data-disabled='0')) or " +
                        "(@data-state='disabled')" +
                        ")" +
                        "]";

        return new XPathy(base + "[" + pred + "]");
    }

    public static XPathy enabledElement() {
        String base = new XPathy().getXpath(); // "//*"

        String disabledPred =
                "descendant-or-self::*[" +
                        "(" +
                        "(@disabled and not(@disabled='false' or @disabled='0')) or " +
                        "(@aria-disabled and not(@aria-disabled='false' or @aria-disabled='0')) or " +
                        "(@data-disabled and not(@data-disabled='false' or @data-disabled='0')) or " +
                        "(@data-state='disabled')" +
                        ")" +
                        "]";

        return new XPathy(base + "[not(" + disabledPred + ")]");
    }
}
