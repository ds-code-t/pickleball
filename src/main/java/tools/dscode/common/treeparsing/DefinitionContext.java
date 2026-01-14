package tools.dscode.common.treeparsing;

import com.xpathy.Attribute;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.intellij.lang.annotations.Language;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyBuilder;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.checked;
import static com.xpathy.Attribute.class_;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.name;
import static com.xpathy.Attribute.placeholder;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.src;
import static com.xpathy.Attribute.tabindex;
import static com.xpathy.Attribute.title;
import static com.xpathy.Attribute.type;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.any;
import static com.xpathy.Tag.div;
import static com.xpathy.Tag.i;
import static com.xpathy.Tag.input;
import static com.xpathy.Tag.select;
import static com.xpathy.Tag.textarea;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.domoperations.elementstates.VisibilityConditions.extractPredicate;
import static tools.dscode.common.domoperations.elementstates.VisibilityConditions.invisible;
import static tools.dscode.common.domoperations.elementstates.VisibilityConditions.visible;
import static tools.dscode.common.treeparsing.RegexUtil.betweenWithEscapes;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.KEY_NAME;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PLACE_HOLDER_MATCH;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.VALUE_TYPE_MATCH;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineOr;

public final class DefinitionContext {

    public static final String FILE_INPUT = "InternalFileInput";


    public static final @Language("RegExp") String NUMERIC_TOKEN =
            "(?<!\\S)-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?!\\S)";


    private DefinitionContext() {
        // utility class
    }


    public static NodeDictionary DEFAULT_NODE_DICTIONARY = new NodeDictionary() {


//        public static final @Language("RegExp") String punc = ",;\\.\\?!";


        ParseNode line = new ParseNode("^.*$") {
            public String onSubstitute(MatchNode self) {
                return " " + self.originalText() + " ";
            }
        };


        ParseNode keyTransform = new ParseNode("\\bas\\s+(?<keyName><<quoteMask>>)(?!\\s*[A-Z])") {
            public String onSubstitute(MatchNode self) {
                String keyName = self.groups().get("keyName");

                return keyName + " " + VALUE_TYPE_MATCH + KEY_NAME;
            }
        };

        ParseNode numericMask = new ParseNode(NUMERIC_TOKEN);

        ParseNode quoteMask = new ParseNode(betweenWithEscapes(BOOK_END, BOOK_END)) {
            @Override
            public String onCapture(String s) {
                System.out.println("##onCaputre quoteMask: " + s);
                return s.substring(1, s.length() - 1);
            }
        };

        ParseNode valueMask = new ParseNode("<<numericMask>>|<<quoteMask>>") {
            @Override
            public String onCapture(MatchNode self) {
                return self.unmask(self.originalText());
            }
        };


        ParseNode valueTransform = new ParseNode("\\s(?<value><<valueMask>>)(?!\\s*[A-Z])(?<unitMatch>\\s+(?<unit>second|minute|hour|day|week|month|year|time|number|integer|decimal|color|text)s?\\b)?") {
            @Override
            public String onSubstitute(MatchNode self) {
                String value = " " + self.groups().get("value") + " ";
                String unit = " " + VALUE_TYPE_MATCH + self.groups().getOrDefault("unit", "").trim().replaceAll("\\s$", "") + " ";

                return value + unit;
            }
        };

        ParseNode position = new ParseNode("#\\d+");


        ParseNode phrase = new ParseNode("^(?<separatorA>\\bthen\\b)?(?<conjunction>\\b(?:and|or)\\b)?(?<separatorB>\\bthen\\b)?\\s*(?<conditional>\\b(?:else\\s+if|else|if)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>.*)$") {
            @Override
            public String onCapture(MatchNode self) {
                if (self.localStateBoolean("separatorA", "separatorB")) {
                    self.putToLocalState("separator", "true");
                }
                String context = self.resolvedGroupText("context");

                if (!context.isEmpty() && Character.isUpperCase(context.charAt(0)))
                    self.putToLocalState("newStartContext", true);
                self.putToLocalState("context", context.toLowerCase());
                String conditional = self.resolvedGroupText("conditional");
                self.putToLocalState("conditional", conditional);
                self.putToLocalState("conjunction", self.resolvedGroupText("conjunction"));
                self.putToLocalState("body", self.resolvedGroupText("body"));
//                String termination = self.resolvedGroupText("punc");
//                if (termination == null || termination.isBlank()) termination = "";
//                self.putToLocalState("termination", termination);
                if (conditional.contains("if")) {
                    self.putToLocalState("assertionType", "conditional");
                    self.localState().put("skip:action", "true");
                    self.localState().put("skip:assertionType", "true");
                } else if (self.localStateBoolean("context")) {
                    self.localState().put("skip:action", "true");
                    self.localState().put("skip:assertion", "true");
                    self.localState().put("skip:assertionType", "true");
                    self.localState().put("skip:defaultAssertion", "true");
                }
                //            self.getAncestor("line").putToLocalState("phrase", self);
//            return colorizeBookends(self.originalText(), BOLD(), BRIGHT_GREEN_TEXT());
                return self.originalText();
            }

            @Override
            public String onSubstitute(MatchNode self) {
                return self.token();
            }
        };

        ParseNode predicate = new ParseNode("(?:\\b(?<predicateType>starting with|ending with|containing|equaling|of)\\s+(?<predicateVal><<valueMask>>))") {
            @Override
            public String onCapture(MatchNode self) {

                String predicateType = self.resolvedGroupText("predicateType");
                predicateType = predicateType.replaceAll("with", "").replaceAll("of", "equaling").trim();
                self.putToLocalState("predicateType", predicateType);
                self.putToLocalState("predicateVal", self.resolvedGroupText("predicateVal"));
                return self.originalText();
            }
        };


        ParseNode elementMatch = new ParseNode("(?:(?<selectionType>every|any|none\\s+of|none|no)\\b\\s*)?(?:(?<elementPosition>\\bfirst|\\blast|<<position>>)\\s*\\b)?(?:(?<state>(?:un)?(?:checked|selected|enabled|disabled|expanded|collapsed|required))\\b\\s*)?(?<text><<valueMask>>)?\\s*\\b(?<type>(?:\\b[A-Z][a-zA-Z]+\\b\\s*)+)(?<elPredicate>(?<predicate>\\s*<<predicate>>))*\\s*(?<atrPredicate>(?:(?:\\band\\s*)?\\bwith\\s+[a-z]+\\s+(?:<<predicate>>|(?:no)?attribute)\\s*))*") {
            @Override
            public String onSubstitute(MatchNode self) {
                self.putToLocalState("fullText", self.unmask(self.groups().get(0)));

//            self.getAncestor("phrase").putToLocalState("elementMatch", self);
                self.putToLocalState("selectionType", self.resolvedGroupText("selectionType")
                        .replaceAll("of", "").trim()
                        .replaceAll("no", "none"));
                String elementPosition = self.resolvedGroupText("elementPosition");
                if (elementPosition.equals("first"))
                    elementPosition = "1";
                self.putToLocalState("elementPosition", elementPosition.replaceAll("#", ""));
                self.putToLocalState("state", self.resolvedGroupText("state"));
                if (self.groups().containsKey("text")) {
                    self.putToLocalState("text", self.resolvedGroupText("text"));
                }
                self.putToLocalState("type", self.resolvedGroupText("type"));


                self.putToLocalState("elPredicate", self.groups().get("elPredicate"));
                self.putToLocalState("atrPredicate", self.groups().get("atrPredicate"));
                return self.token();
            }
        };

        ParseNode valueTypes = new ParseNode("(?<valueTypes>\\s(?:(?:and\\s+)?[a-z-]+\\s+of\\s+)+)(?<element><<elementMatch>>)") {
            @Override
            public String onSubstitute(MatchNode self) {
                String valueTypes = self.resolvedGroupText("valueTypes").replaceAll("\\b(?:and|of)\\b", "").trim();
                String elementToken = self.groups().get("element");
                MatchNode elementMatchNode = self.getMatchNode(elementToken);

                elementMatchNode.putToLocalState("valueTypes", valueTypes);

//                return elOrValToken;
                return " " + self.groups().get("element") + " ";
            }

        };


        ParseNode assertionType = new ParseNode("\\b(ensure|verify)\\b") {
            @Override
            public String onSubstitute(MatchNode self) {
                self.parent().putToLocalState("assertionType", self.originalText());
                self.parent().localState().put("skip:action", "true");
                return self.originalText();
            }
        };

        ParseNode action = new ParseNode("\\b(?<base>select|press|dragAndDrop|double click|right click|hover|move|click|enter|scroll|wait|overwrite|save|creates? and attach|attach|switch|close|accept|dismiss)(?:s|ed|ing|es)?\\b") {
            @Override
            public String onCapture(MatchNode self) {
                System.out.println("##onCaputre action: " + self.originalText() + "");
                self.parent().putToLocalState("action", self.resolvedGroupText("base"));
                self.parent().putToLocalState("operationIndex", self.start);

                return self.resolvedGroupText("base").replaceAll("move", "hover");
            }
        };


        ParseNode no = new ParseNode("\\bno\\b") {
            @Override
            public String onCapture(MatchNode self) {
                self.parent().putToLocalState(self.originalText(), self.originalText());
//                return self.originalText();
                return " ";
            }
        };

        ParseNode assertion = new ParseNode("\\b(?:starts?\\s+with|ends?\\s+with|contains?|match(?:es)?|displayed|(?:un)?selected|(?:un)?checked|enabled|disabled|equals?|less\\s+than|greater\\s+than|has\\s+values?|(?:has|is)\\s+blank)\\b") {
            @Override
            public String onCapture(MatchNode self) {
                String assertion = self.originalText().trim()
                        .replaceAll("(start|end|contain|match|equal|values)(?:es|s)", "$1")
                        .replaceAll("^(?:is|has)", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                self.parent().putToLocalState("assertion", assertion);


                self.parent().putToLocalState("operationIndex", self.start);

                return self.originalText();
            }
        };


        ParseNode defaultAssertion = new ParseNode("<<elementMatch>>\\s*(?<defaultAssertion>is)\\s*<<elementMatch>>") {
            @Override
            public String onSubstitute(MatchNode self) {

                MatchNode parentNode = self.parent();
                if (!parentNode.localStateBoolean("context", "action", "assertion")) {

                    parentNode.putToLocalState("assertion", "equal");
                    parentNode.putToLocalState("operationIndex", self.groups().start("defaultAssertion"));


                }
                return self.originalText();
            }
        };


        ParseNode itPlaceholder = new ParseNode("\\bit\\b") {
            @Override
            public String onSubstitute(MatchNode self) {
                return " " + PLACE_HOLDER_MATCH + " ";
            }
        };

        ParseNode reindex = new ParseNode("<<elementMatch>>") {
            @Override
            public String onSubstitute(MatchNode self) {

                MatchNode elementMatchNode = self.getMatchNode(self.originalText());
                elementMatchNode.start = self.start;

                return self.originalText();
            }
        };

        ParseNode root = buildFromYaml("""
                line:
                  - quoteMask
                  - position
                  - numericMask
                  - keyTransform
                  - valueMask
                  - phrase:
                    - predicate
                    - itPlaceholder
                    - valueTransform
                    - elementMatch
                    - no
                    - valueTypes
                    - assertionType
                    - reindex
                    - action
                    - assertion
                    - defaultAssertion
                """);


    };

    public static ExecutionDictionary DEFAULT_EXECUTION_DICTIONARY = new ExecutionDictionary() {
        @Override
        protected void register() {

            //
            // Frame
            //
            registerIframe("IFrame").children("Frame", "Frames", "IFrames", "Iframe", "Iframes")
                    .and(
                            (category, v, op) ->
                                    combineOr(Tag.iframe, Tag.frame)
                    ).or(
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(Tag.any, id, v, op, v != null),
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(Tag.any, title, v, op, v != null),
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(Tag.any, name, v, op, v != null)
                    );


            category(FILE_INPUT)
                    .and(
                            (category, v, op) ->
                                    Tag.input.byAttribute(type).equals("file")
                    );


            category("Icon").children("Icons").inheritsFrom("forLabel", CONTAINS_TEXT)
                    .or(
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(Tag.img, class_, ValueWrapper.createValueWrapper("'icon'"), Op.CONTAINS),
                            (category, v, op) -> XPathy.from(Tag.i),
                            (category, v, op) -> XPathy.from(Tag.any).byAttribute(role).withCase(LOWER).equals("icon")
                    ).and(
                            (category, v, op) -> {
                                ValueWrapper strippedValue = v == null ? null : v.normalizeLowerCaseAndStripAllWhiteSpace();
                                return XPathyBuilder.buildIfAllTrue(any, src, strippedValue, op, strippedValue != null);
                            }
                    );


            category("Button").children("Buttons").inheritsFrom("forLabel", "htmlNaming", CONTAINS_TEXT)
                    .or(
                            (category, v, op) -> XPathy.from(Tag.button),
                            (category, v, op) -> XPathy.from(Tag.img).byAttribute(role).equals("button"),
                            (category, v, op) -> XPathy.from(Tag.a).byAttribute(role).equals("button")
                    );

            category("Expandable Section").children("Expandable Sections").inheritsFrom("htmlNaming", CONTAINS_TEXT)
                    .and(
                            (category, v, op) ->
                                    combineOr(
                                            XPathy.from(div).byAttribute(class_).withCase(LOWER).contains("expand"),
                                            XPathy.from(div).byAttribute(class_).withCase(LOWER).contains("collapse")
                                    )
                    );

            category("Expandable Header").children("Expandable Headers").inheritsFrom("htmlNaming", CONTAINS_TEXT)
                    .and(
                            (category, v, op) -> XPathy.from(div)
                                    .byAttribute(class_).withCase(LOWER).contains("header")
                                    .and().byHaving().parent().byAttribute(class_).withCase(LOWER).contains("collapsible")
                    );

            category("Expandable Icon").children("Expandable Icons").inheritsFrom("htmlNaming", CONTAINS_TEXT)
                    .and(
                            (category, v, op) -> any.byAttribute(role).equals("button").and().byAttribute(Attribute.custom("aria-expanded")).haveIt()
                    );


            category("Submit Button").children("Submit Buttons").or(
                    (category, v, op) -> input.byAttribute(type).equals("submit")
            );

            //
            // Link
            //
            category("Link").children("Links").inheritsFrom("Text")
                    .or(
                            (category, v, op) -> XPathy.from(any).byAttribute(role).equals("link"),
                            (category, v, op) -> XPathy.from(any).byAttribute(aria_label).equals("link"),
                            (category, v, op) -> XPathy.from(Tag.a)
                    );


            category("Dropdown").children("Dropdowns").inheritsFrom("forLabel", "htmlNaming")
                    .and((category, v, op) ->
                            XPathy.from(select))
                    .or(
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(select, id, v, op, v != null),
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(select, title, v, op, v != null),
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(select, name, v, op, v != null)
                    );

            category("Modal").children("Modals", "Dialog", "Dialogs").inheritsFrom(CONTAINS_TEXT, "forLabel", "htmlNaming")
                    .and(
                            (category, v, op) ->
                                    combineOr(
                                            XPathy.from(div).byAttribute(role).equals("dialog"),
                                            XPathy.from(div).byAttribute(Attribute.custom("aria-model")).equals("true"),
                                            XPathy.from(div).byAttribute(id).equals("modal")
                                    )
                    );
            category("Close Button").children("Close Buttons")
                    .and(
                            (category, v, op) ->
                                    combineOr(
                                            XPathy.from(Tag.button),
                                            XPathy.from(Tag.img).byAttribute(role).equals("button"),
                                            XPathy.from(Tag.a).byAttribute(role).equals("button")
                                    )
                    )
                    .or(
                            (category, v, op) -> XPathyBuilder.build(any, id, ValueWrapper.createValueWrapper("'close'"), Op.STARTS_WITH),
                            (category, v, op) -> XPathyBuilder.build(any, title, ValueWrapper.createValueWrapper("'close'"), Op.STARTS_WITH),
                            (category, v, op) -> XPathyBuilder.build(any, name, ValueWrapper.createValueWrapper("'close'"), Op.STARTS_WITH),
                            (category, v, op) -> XPathyBuilder.build(any, aria_label, ValueWrapper.createValueWrapper("'close'"), Op.STARTS_WITH)
                    );


            category("Tab").children("Tabs").inheritsFrom(CONTAINS_TEXT)
                    .or(
                            (category, v, op) -> XPathy.from(Tag.any.byAttribute(role).equals("tab")),
                            (category, v, op) -> XPathy.from(Tag.img).byAttribute(tabindex).haveIt()

                    );

            //
            // Textbox  (two registration blocks preserved exactly)
            //
            category("Textbox").children("Textboxes").inheritsFrom("forLabel", "htmlNaming")
                    .and((category, v, op) ->
                            combineOr(
                                    input.byAttribute(type).equals("text"),
                                    input.byAttribute(type).equals("password"),
                                    input.byAttribute(type).equals("email"))
                    )
                    .or(
                            (category, v, op) ->
                                    XPathyBuilder.buildIfAllTrue(input, placeholder, v, op, v != null)
                    );

            category("Textarea").children("Textareas").inheritsFrom("forLabel", "htmlNaming")
                    .and((category, v, op) ->
                            XPathy.from(textarea)
                    )
                    .or(
                            (category, v, op) ->
                                    XPathyBuilder.buildIfAllTrue(textarea, placeholder, v, op, v != null)
                    );


            category("Radio Button").children("Radio Buttons").inheritsFrom("forLabel", "htmlNaming")
                    .and((category, v, op) ->
                            combineOr(
                                    Tag.input.byAttribute(type).equals("radio")
                            )
                    );

            category("Checkbox").children("Checkboxes").inheritsFrom("forLabel", "htmlNaming")
                    .and((category, v, op) ->
                            combineOr(
                                    Tag.input.byAttribute(type).equals("checkbox"),
                                    XPathy.from(Tag.custom("mat-checkbox"))
                            )
                    );

            category("Toggle").children("Toggles").inheritsFrom("forLabel", "htmlNaming")
                    .and((category, v, op) ->
                            combineOr(
                                    Tag.input.byAttribute(checked).haveIt(),
                                    Tag.input.byAttribute(Attribute.custom("aria-checked")).haveIt(),
                                    XPathy.from(Tag.custom("mat-slide-toggle"))
                            )
                    );

            category("Option").children("Options").inheritsFrom(CONTAINS_TEXT)
                    .and((category, v, op) ->
                            XPathy.from(Tag.option)
                    );

            category("Row").children("Rows").inheritsFrom(CONTAINS_TEXT)
                    .and((category, v, op) ->
                            XPathy.from("//*[self::tr or @role='row'][not(descendant::*[self::tr or @role='row'])]")
                    );


            category(BASE_CATEGORY).and(
                    (category, v, op) -> {
                        XPathy selfInvisible = any.byCondition(invisible());
                        String invisiblePredicate = extractPredicate("//*", selfInvisible.getXpath());

                        XPathy selfVisible = any.byCondition(visible());
                        String visiblePredicate = extractPredicate("//*", selfVisible.getXpath());
                        return XPathy.from(
                                "//*[" +
                                        visiblePredicate +
                                        " and not(ancestor::*[" + invisiblePredicate + "])]"
                        );
                    }
            );

            category("visible")
                    .and(
                            (category, v, op) -> {
                                XPathy selfInvisible = any.byCondition(invisible());
                                String invisiblePredicate = extractPredicate("//*", selfInvisible.getXpath());

                                XPathy selfVisible = any.byCondition(visible());
                                String visiblePredicate = extractPredicate("//*", selfVisible.getXpath());

                                return XPathy.from(
                                        "//*[" +
                                                visiblePredicate +
                                                " and not(ancestor::*[" + invisiblePredicate + "])]"
                                );
                            }
                    );


            category("forLabel")
                    .or(

                            (category, v, op) -> {
                                if (v == null || v.isNullOrBlank()) {
                                    return null; // no label text to match, skip this builder
                                }
                                return new XPathy("//*[@id][@id = //*[normalize-space(text())='" + v + "']/@for]");
                            }
                    );


            category("htmlNaming")
                    .or(
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(any, id, v, op, v != null),
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(any, title, v, op, v != null),
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(any, name, v, op, v != null),
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(any, aria_label, v, op, v != null)
                    );

            //
            // "*" fallback OR builders
            //

            category("*")
                    .or(
                            (category, v, op) ->
                                    XPathy.from(any).byAttribute(role).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                            (category, v, op) ->
                                    XPathy.from(any).byAttribute(title).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                            (category, v, op) ->
                                    XPathy.from(any).byAttribute(id).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                            (category, v, op) ->
                                    XPathy.from(any).byAttribute(name).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase()),

                            (category, v, op) ->
                                    XPathy.from(any).byAttribute(aria_label).withCase(LOWER).withNormalizeSpace().equals(category.toLowerCase())
                    );

        }
    };


    private static final ThreadLocal<ExecutionDictionary> EXECUTION_DICTIONARY_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> DEFAULT_EXECUTION_DICTIONARY);


    public static ExecutionDictionary getExecutionDictionary() {
        return EXECUTION_DICTIONARY_THREAD_LOCAL.get();
    }


    public static void setExecutionDictionaryDictionary(ExecutionDictionary dictionary) {
        EXECUTION_DICTIONARY_THREAD_LOCAL.set(dictionary);
    }


    public static void resetExecutionDictionary() {
        EXECUTION_DICTIONARY_THREAD_LOCAL.set(DEFAULT_EXECUTION_DICTIONARY);
    }


    private static final ThreadLocal<NodeDictionary> NODE_DICTIONARY_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> DEFAULT_NODE_DICTIONARY);


    public static NodeDictionary getNodeDictionary() {
        return NODE_DICTIONARY_THREAD_LOCAL.get();
    }


    public static void setNodeDictionary(NodeDictionary dictionary) {
        NODE_DICTIONARY_THREAD_LOCAL.set(dictionary);
    }


    public static void resetNodeDictionary() {
        NODE_DICTIONARY_THREAD_LOCAL.set(DEFAULT_NODE_DICTIONARY);
    }

}
