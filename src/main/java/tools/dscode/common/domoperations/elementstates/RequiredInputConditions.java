package tools.dscode.common.domoperations.elementstates;

import com.xpathy.XPathy;

public final class RequiredInputConditions {

    private RequiredInputConditions() {
        // utility class
    }

    public static XPathy requiredElement() {
        String base = new XPathy().getXpath(); // "//*"

        String pred =
                "descendant-or-self::*[" +
                        "(" +
                        "(@validationtype='required') or " +
                        "(@required and not(@required='false' or @required='0')) or " +
                        "(@aria-required and not(@aria-required='false' or @aria-required='0')) or " +
                        "(@data-required and not(@data-required='false' or @data-required='0')) or " +
                        "(@data-state='required')" +
                        ")" +
                        "]";

        return new XPathy(base + "[" + pred + "]");
    }

    public static XPathy notRequiredElement() {
        String base = new XPathy().getXpath(); // "//*"

        String requiredPred =
                "descendant-or-self::*[" +
                        "(" +
                        "(@validationtype='required') or " +
                        "(@required and not(@required='false' or @required='0')) or " +
                        "(@aria-required and not(@aria-required='false' or @aria-required='0')) or " +
                        "(@data-required and not(@data-required='false' or @data-required='0')) or " +
                        "(@data-state='required')" +
                        ")" +
                        "]";

        return new XPathy(base + "[not(" + requiredPred + ")]");
    }
}
