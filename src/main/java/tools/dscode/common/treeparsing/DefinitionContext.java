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
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.customElementSuffixPredicate;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepestOnlyXPath;
import static tools.dscode.common.util.debug.DebugUtils.disableBaseElement;
import static tools.dscode.common.util.debug.DebugUtils.onMatch;
import static tools.dscode.common.util.debug.DebugUtils.printDebug;

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


        ParseNode phrase = new ParseNode("^\\s*(?<separatorA>\\b[tT]hen\\b\\s*)?(?<conjunction>\\b(?:and|or)\\b\\s*)?(?<separatorB>\\b[tT]hen\\b\\s*)?\\s*(?<conditional>\\b(?:until|else\\s+if|else|if)\\b)?\\s*(?i:(?<context>from|after|before|for|in|below|above|left of|right of)\\b)?(?<body>.*)$") {
            @Override
            public String onCapture(MatchNode self) {
                String separator = self.resolvedGroupText("separatorA");
                if (separator.isEmpty())
                    separator = self.resolvedGroupText("separatorB");

                onMatch("##parsing-phrase: '" + separator + "'", (matchString) -> {
                    System.out.println(matchString + "  , for : " + self.originalText());
                });

                if (!separator.isEmpty()) {
                    self.putToLocalState("separator", "true");
                }

                String context = self.resolvedGroupText("context");


                boolean upperCaseContext = !context.isEmpty() && Character.isUpperCase(context.charAt(0));

                if (upperCaseContext || (!separator.isEmpty() && Character.isUpperCase(separator.charAt(0))))
                    self.putToLocalState("newStartContext", true);

                self.putToLocalState("context", context.toLowerCase());
                String conditional = self.resolvedGroupText("conditional");
                self.putToLocalState("conditional", conditional);
                if (conditional.equalsIgnoreCase("until")) {
                    conditional = "if";
                }

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

                String returnString = separator.isEmpty() ? self.originalText() : self.originalText().replaceFirst(separator, "");
                if (upperCaseContext)
                    return returnString.replaceFirst(context, context.toLowerCase());

                return returnString;
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


        ParseNode elementMatch = new ParseNode("(?:(?<selectionType>every|any|none\\s+of|none|no)\\b\\s*)?(?:(?<elementPosition>\\bfirst|\\blast|<<position>>)\\b\\s*)?(?:(?<state>(?:un|non-?)?(?:checked|selected|enabled|disabled|expanded|collapsed|required|empty|blank))\\b\\s*)?(?<text><<valueMask>>)?\\s*\\b(?<type>(?:\\b[A-Z][a-zA-Z]+\\b\\s*)+)(?<elPredicate>(?<predicate>\\s*<<predicate>>))*\\s*(?<atrPredicate>(?:(?:\\band\\s*)?\\bwith\\s+[a-z]+\\s+(?:<<predicate>>|(?:no)?attribute)\\s*))*") {
            @Override
            public String onSubstitute(MatchNode self) {
                printDebug("##parsedefinition-elementMatch: " + self.originalText());
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

        ParseNode action = new ParseNode("\\b(?<base>select|press|dragAndDrop|double click|right click|hover|move|click|enter|scroll|wait|overwrite|clear|save|creates? and attach|attach|switch|close|accept|dismiss)(?:s|ed|ing|es)?\\b") {
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


            category("Loading").flags()
                    .or(
                            (category, v, op) ->
                                    XPathy.from("//*[" +
                                            "       self::progress" +
                                            "    or self::meter" +
                                            "    or @aria-busy = 'true'" +
                                            "    or @role = 'progressbar'" +
                                            "    or @role = 'status'" +
                                            "    or (@role = 'progressbar' and (@aria-valuenow or @aria-valuemin or @aria-valuemax))" +
                                            "    or @data-loading = 'true'" +
                                            "    or @data-state = 'loading'" +
                                            "    or contains(@data-testid, 'loading')" +
                                            "    or contains(@data-testid, 'spinner')" +
                                            "]")
                    );

            category(FILE_INPUT).flags(CategoryFlags.NON_DISPLAY_ELEMENT)
                    .and(
                            (category, v, op) ->
                                    Tag.input.byAttribute(type).equals("file")
                    );


            category("Icon").children("Icons").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, CONTAINS_TEXT)
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


            category("Button").children("Buttons").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, CONTAINS_TEXT)
                    .or(
                            (category, v, op) -> XPathy.from(Tag.button),
                            (category, v, op) -> XPathy.from(Tag.img).byAttribute(role).equals("button"),
                            (category, v, op) -> XPathy.from(Tag.a).byAttribute(role).equals("button")
                    );

            category("Section").children("Sections")
                    .addBase("//div")
                    .and(
                            (category, v, op) -> {
                                if (v == null || v.isNull())
                                    return null;
                                String textXpath = "[" + XPathy.from("descendant::*")
                                        .byHaving(deepNormalizedText(v, op)).getXpath().replaceAll("^//\\*", "") + "]";
                                printDebug("##textXpath Section: " + textXpath);
                                String xpath1 = XPathy.from("//div" + textXpath +
                                        "[self::div[" +
                                        "    descendant::*[self::select or self::input or self::textarea or self::textarea]" +
                                        "    or" +
                                        "    self::div[" +
                                        "    descendant::div" +
                                        "    and not(contains( translate(@class, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'header' ))" +
                                        "    and count(child::*[.//text()]) >= 2" +
                                        "    ]" +
                                        "]]").getXpath();

                                return XPathy.from(deepestOnlyXPath(xpath1));
                            }
                    );


            category("Menu").children("Menus", "Menu Item", "Menu Items")
                    .addBase("//li[contains(@class, 'menu-item')]")
                    .and(
                            (category, v, op) -> {
                                if (v == null || v.isNull())
                                    return null;
                                String textXpath = "[" + XPathy.from("descendant-or-self::*")
                                        .byHaving(deepNormalizedText(v, op)).getXpath().replaceAll("^//\\*", "") + "]";
                                printDebug("##textXpath Section: " + textXpath);
                                return XPathy.from("//li[a[1]" + textXpath  + "]");
                            }
                    );




            category("Modal").children("Modals", "Dialog", "Dialogs").andAnyCategories(CONTAINS_TEXT, "forLabel", HTML_NAME_ATTRIBUTES)
                    .addBase("//div")
                    .and(
                            (category, v, op) ->
                                    combineOr(
                                            XPathy.from(div).byAttribute(role).equals("dialog"),
                                            XPathy.from(div).byAttribute(Attribute.custom("aria-model")).equals("true"),
                                            XPathy.from(div).byAttribute(id).equals("modalWrapper")
                                    ),
                            (category, v, op) -> XPathy.from("//div[.//text()]")
                    );

            category("Expandable Section").children("Expandable Sections").andAnyCategories(HTML_NAME_ATTRIBUTES, CONTAINS_TEXT)
                    .addBase("//div")
                    .and(
                            (category, v, op) ->
                                    combineOr(
                                            XPathy.from(div).byAttribute(class_).withCase(LOWER).contains("expand"),
                                            XPathy.from(div).byAttribute(class_).withCase(LOWER).contains("collapse")
                                    )
                    );

            category("Expandable Header").children("Expandable Headers").andAnyCategories(HTML_NAME_ATTRIBUTES, CONTAINS_TEXT)
                    .addBase("//div")
                    .and(
                            (category, v, op) -> XPathy.from(div)
                                    .byAttribute(class_).withCase(LOWER).contains("header")
                                    .and().byHaving().parent().byAttribute(class_).withCase(LOWER).contains("collapsible")
                    );

            category("Expandable Icon").children("Expandable Icons").andAnyCategories(HTML_NAME_ATTRIBUTES, CONTAINS_TEXT)
                    .and(
                            (category, v, op) -> any.byAttribute(role).equals("button").and().byAttribute(Attribute.custom("aria-expanded")).haveIt()
                    );


            category("Submit Button").children("Submit Buttons").or(
                    (category, v, op) -> input.byAttribute(type).equals("submit")
            );

            //
            // Link
            //
            category("Link").children("Links").inheritsFrom(CONTAINS_TEXT)
                    .or(
                            (category, v, op) -> XPathy.from(any).byAttribute(role).equals("link"),
                            (category, v, op) -> XPathy.from(any).byAttribute(aria_label).equals("link"),
                            (category, v, op) -> XPathy.from(Tag.a)
                    );


            category("Dropdown").children("Dropdowns").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, "genericLabel")
                    .addBase("//select");


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
            category("Textbox").children("Textboxes").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, "genericLabel", "placeholderLabel")
                    .addBase("//input")
                    .or(
                            (category, v, op) -> input.byAttribute(type).equals("text"),
                            (category, v, op) -> input.byAttribute(type).equals("password"),
                            (category, v, op) -> input.byAttribute(type).equals("email"),
                            (category, v, op) -> input.byAttribute(type).equals("number"),
                            (category, v, op) -> input.byAttribute(Attribute.custom("data-ctl")).contains("TextInput")
                    );

            category("Date Textbox").children("Date Textboxes").inheritsFrom("Textbox")
                    .addBase("//input")
                    .and((category, v, op) ->
                            combineOr(
                                    input.byAttribute(Attribute.custom("data-ctl"))
                                            .withCase(LOWER).contains("date"),
                                    input.byAttribute(Attribute.custom("validationtype")).withCase(LOWER).contains("date"),
                                    input.byAttribute(aria_label).withCase(LOWER).contains("date")
                            )
                    );

            category("Textarea").children("Textareas").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, "genericLabel", "placeholderLabel")
                    .addBase("//textarea");


            category("Radio Button").children("Radio Buttons").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, "genericLabel")
                    .addBase("//input[@type='radio']");


            category("Checkbox").children("Checkboxes").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, "genericLabel")
                    .and((category, v, op) ->
                            combineOr(
                                    Tag.input.byAttribute(type).equals("checkbox"),
                                    XPathy.from(Tag.custom("mat-checkbox"))
                            )
                    );

            category("Toggle").children("Toggles").andAnyCategories("forLabel", HTML_NAME_ATTRIBUTES, "genericLabel")
                    .and((category, v, op) ->
                            combineOr(
                                    Tag.input.byAttribute(checked).haveIt(),
                                    Tag.input.byAttribute(Attribute.custom("aria-checked")).haveIt(),
                                    XPathy.from(Tag.custom("mat-slide-toggle"))
                            )
                    );

            category("Option").children("Options").inheritsFrom(CONTAINS_TEXT)
                    .addBase("//option");



            category("Table").children("Tables").inheritsFrom(CONTAINS_TEXT)
                    .and((category, v, op) ->
                            XPathy.from("//*[self::table or @role='table' or self::*" + customElementSuffixPredicate("table") + "][not(descendant::table)]")
                    );

            category("Row").children("Rows").inheritsFrom(CONTAINS_TEXT)
                    .and((category, v, op) ->
                            XPathy.from("//*[self::tr or @role='row' or self::*" +  customElementSuffixPredicate("row") +  " ][not(descendant::table)]")
                    );

            category("Header Row").children("Headers").inheritsFrom(CONTAINS_TEXT)
                    .and((category, v, op) ->
                            XPathy.from("//*[self::thead or self::tr[th] or self::*" + customElementSuffixPredicate("header") + " ][not(descendant::table)]")
                    );

            category("Header").children("Column Headers").inheritsFrom(CONTAINS_TEXT)
                    .and((category, v, op) ->
                            XPathy.from("//*[self::th or @role='columnheader' or self::*" + customElementSuffixPredicate("header") + " ][not(descendant::table)]")
                    );

            category("Cell").children("Cells").inheritsFrom(CONTAINS_TEXT)
                    .and((category, v, op) ->
                            XPathy.from("//*[self::td or self::th or @role='cell' or @role='gridcell' or @role='columnheader' or @role='rowheader' or self::*" + customElementSuffixPredicate("cell") + " ][not(descendant::table)]")
                    );



            category(BASE_CATEGORY).and(
                    (category, v, op) -> {
                        if (disableBaseElement)
                            return null;
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


            category("genericLabel")
                    .and(
                            (category, v, op) -> {
                                if (v == null || v.isNullOrBlank()) {
                                    return null; // no label text to match, skip this builder
                                }

                                XPathy returnXpathy = new XPathy(
                                        "//*[ancestor-or-self::*[position() <= 5]" +
                                                singleControlElementContainer +
                                                "       [preceding-sibling::*[count(descendant::div//text())<2]" +
                                                "           [" +
                                                "               normalize-space(.)!='' or " +
                                                "               descendant::*[self::input or self::textarea or self::select]" +
                                                "           ][1]       " + getContainsText(v, op) + "          [not(descendant::*[self::input or self::textarea or self::select][@type='hidden'])]" +
                                                "       ]" +
                                                "]"
                                );
                                printDebug("##returnXpathy-" + category + ": " + returnXpathy.getXpath());
                                return returnXpathy;

                            }
                    );


            category("placeholderLabel")
                    .and(
                            (category, v, op) -> XPathyBuilder.buildIfAllTrue(any, placeholder, v, op, v != null)
                    );


            category("forLabel")
                    .and(
                            (category, v, op) -> {
                                if (v == null || v.isNullOrBlank()) {
                                    return null; // no label text to match, skip this builder
                                }

                                XPathy returnXpath = combineOr(
                                        new XPathy("//*[@id = (ancestor::div[3]//descendant::*" + getDirectText(v, op) + "[@for][1]/@for)]"),
                                        new XPathy("//*[ancestor-or-self::*[position() <= 7]" +
                                                "  [preceding-sibling::*[1]        " +
                                                getContainsText(v, op) +
                                                "         [not(descendant::button or descendant::input or descendant::textarea or descendant::select or descendant::a)]" +
                                                "  ]" +
                                                "]")
                                );
                                printDebug("##textXpath forLabel:2 " + returnXpath);
                                return returnXpath;
//                                        new XPathy("//*[ancestor-or-self::*[position() <= 5][preceding-sibling::*[1][not(descendant::*[self::button or self::input or self::textarea or self::select or self::a])][self::*" + textXpath + "]]")

                            }
                    );


            category(HTML_NAME_ATTRIBUTES)
                    .or(
                            (category, v, op) -> {
                                if (v == null)
                                    return null;
                                ValueWrapper strippedValueWrapper = v.stripAllNonLetters();
                                return combineOr(
                                        XPathyBuilder.build(any, id, strippedValueWrapper, op),
                                        XPathyBuilder.build(any, title, strippedValueWrapper, op),
                                        XPathyBuilder.build(any, name, strippedValueWrapper, op),
                                        XPathyBuilder.build(any, Attribute.custom("node_name"), strippedValueWrapper, op),
                                        XPathyBuilder.build(any, Attribute.custom("data-node-id"),strippedValueWrapper, op)
                                );
                            }
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
