package tools.dscode.common.domoperations;

import com.xpathy.XPathy;
import tools.dscode.common.treeparsing.PhraseExecution;

import static tools.dscode.common.treeparsing.PhraseExecution.getXPathyContext;

public record XPathData(String context, XPathy xPathy, boolean isFrom, boolean isNewContext) {
    public XPathData(PhraseExecution pe) {
        this(pe.context, getXPathyContext(pe.context, pe.elements), pe.context.equals("from"), pe.newContext);
    }

    public XPathData(XPathData xPathData, XPathy modifiedXPathy) {
        this(xPathData.context, modifiedXPathy, xPathData.isFrom, xPathData.isNewContext);
    }

    public XPathData(PhraseExecution.ElementMatch elementMatch) {
        this("", elementMatch.xPathy, false, false);
    }
}