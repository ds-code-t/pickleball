package tools.dscode.common.treeparsing;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import tools.dscode.common.domoperations.XPathyRegistry;

import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.domoperations.XPathyMini.applyAttrOp;
import static tools.dscode.common.domoperations.XPathyMini.applyTextOp;
import static tools.dscode.common.domoperations.XPathyRegistry.orAll;

public class LineData {
    public enum ElementType {
        HTML, ALERT, BROWSER, BROWSER_TAB, URL, VALUE
    }


    List<Phrase> phrases = new ArrayList<>();

    public LineData(MatchNode lineNode) {
        List<MatchNode> phrasesNodes = lineNode.getChildList("phrase");
        for (MatchNode phraseNode : phrasesNodes) {
            Phrase phrase = new Phrase(phraseNode);
            phrases.add(phrase);
        }
    }


    public static class Phrase {
        String text;
        MatchNode valueNode;
        MatchNode phraseNode;
        MatchNode context;
        MatchNode action;
        String assertion;
        String assertionType;
        Element element;

        public enum PhraseType {
            CONTEXT, ACTION, ASSERTION
        }

        public PhraseType type;

        public Phrase(MatchNode phraseNode) {
            this.phraseNode = phraseNode;
            System.out.println("@@phraseNode::: " + phraseNode);
            phraseNode.children.asMap().forEach((key, matchNodes) -> {
                for (MatchNode node : matchNodes) {
                    System.out.println(key + " => " + node.name());
                }
            });
            text = phraseNode.toString();
            valueNode = (MatchNode) phraseNode.getChild("value");
            MatchNode assertionTypeNode;
            if ((context = phraseNode.getChild("context")) == null) {
                if ((action = phraseNode.getChild("action")) == null) {
                    if ((assertionTypeNode = phraseNode.getChild("assertionType")) != null) {
                        assertionType = assertionTypeNode.modifiedText();
                        assertion = phraseNode.getFromLocalState("assertion").toString();
                        type = PhraseType.ASSERTION;
                    }
                } else {
                    type = PhraseType.ACTION;
                }
            } else {
                type = PhraseType.CONTEXT;
            }
//
//            List<MatchNode> elementNodes = phraseNode.getChildList("element");
//            for (MatchNode elementNode : elementNodes) {
//                element = new Element(elementNode);
//            }
        }
    }


    public static class Element {
        String text;
        String type;
        Attribute attribute;
        XPathy xPathy;
        ElementType elementType;

        public Element(MatchNode elementNode) {
            this.text = (String) elementNode.getFromLocalState("text");
            this.type = (String) elementNode.getFromLocalState("type");

            try {
                this.elementType = ElementType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                this.elementType = ElementType.HTML;
            }

            if (!this.elementType.equals(ElementType.HTML))
                return;

            XPathyRegistry.Op textOp = text == null ? null : XPathyRegistry.Op.EQUALS;
            xPathy = orAll(type, text, textOp).orElse(XPathy.from(Tag.any));
            MatchNode predicateNode = (MatchNode) elementNode.getFromGlobalState((String) elementNode.getFromLocalState("predicate"));

            if (predicateNode != null) {
                this.attribute = new Attribute((String) elementNode.getFromLocalState("attrName"), (String) predicateNode.getFromLocalState("predicateType"), (String) predicateNode.getFromLocalState("predicateVal"));

                XPathyRegistry.Op op = switch (attribute.predicateType) {
                    case null -> null;
                    case String s when s.isBlank() -> null;
                    case String s when s.startsWith("equal") -> XPathyRegistry.Op.EQUALS;
                    case String s when s.startsWith("contain") -> XPathyRegistry.Op.CONTAINS;
                    case String s when s.startsWith("start") -> XPathyRegistry.Op.STARTS_WITH;
                    default -> null;
                };
                System.out.println("@@interXPathy: " + xPathy + "  -op: " + op + "  -attribute: " + attribute + "");
                if (attribute.attrName.equals("TEXT"))
                    xPathy = applyTextOp(xPathy, op, text);
                else
                    xPathy = applyAttrOp(xPathy, com.xpathy.Attribute.custom(attribute.attrName), op, attribute.predicateVal);
            }
        }
    }


    public static class Attribute {
        String attrName;
        String predicateType;
        String predicateVal;

        public Attribute(String attrName, String predicateType, String predicateVal) {
            this.attrName = attrName == null || attrName.isBlank() ? "TEXT" : attrName;
            this.predicateType = predicateType;
            this.predicateVal = predicateVal;
        }
    }

}
