package tools.dscode.common.domoperations;

import com.xpathy.XPathy;
import tools.dscode.common.treeparsing.PhraseExecution;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static tools.dscode.common.domoperations.XPathyUtils.refine;
import static tools.dscode.common.treeparsing.PhraseExecution.getXPathyContext;

public record XPathData(String context, XPathy xPathy, boolean isFrom, boolean isNewContext, Set<ExecutionDictionary.CategoryFlags> categoryFlags) {
    public XPathData(PhraseExecution pe) {
        this(pe.context, getXPathyContext(pe.context, pe.elements), pe.context.equals("from"), pe.newContext, pe.elements.getFirst().categoryFlags);
    }

    public XPathData(PhraseExecution.ElementMatch elementMatch) {
        this("", elementMatch.xPathy, false, false, elementMatch.categoryFlags);
    }



    public XPathData(XPathData xPathData1, XPathData xPathData2) {
        this(xPathData2.context, refine(xPathData1.xPathy(), xPathData2.xPathy()), xPathData2.isFrom, xPathData1.isNewContext,  Stream.concat(xPathData1.categoryFlags.stream(), xPathData2.categoryFlags.stream())
                .collect(Collectors.toSet()));
    }

//    public XPathData(XPathData xPathData, XPathy modifiedXPathy) {
//        this(xPathData.context, modifiedXPathy, xPathData.isFrom, xPathData.isNewContext, xPathData.categoryFlags);
//    }
}