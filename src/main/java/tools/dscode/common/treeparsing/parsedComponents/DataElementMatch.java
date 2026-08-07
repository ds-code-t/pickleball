package tools.dscode.common.treeparsing.parsedComponents;

import tools.dscode.common.dataelements.DataAttribute;
import tools.dscode.common.dataelements.DataElementGroup;
import tools.dscode.common.dataelements.DataElementKind;
import tools.dscode.common.dataelements.DataElementRegistration;
import tools.dscode.common.dataelements.DataElementRegistry;
import tools.dscode.common.dataelements.DataQuery;
import tools.dscode.common.dataelements.DataResultUse;
import tools.dscode.common.treeparsing.MatchNode;

import java.util.Locale;
import java.util.Optional;

public final class DataElementMatch extends ElementMatch {
    private final DataElementRegistration registration;
    private final boolean explicitSource;

    public DataElementMatch(PhraseData phraseData, MatchNode elementNode) {
        super(phraseData, elementNode);
        registration = DataElementRegistry.require(category);
        explicitSource = supportsExplicitSource(registration.kind())
                && elementNode.getFromLocalState("text") != null;
    }

    public DataElementMatch(PhraseData phraseData, ElementMatch elementMatch) {
        super(phraseData, elementMatch);
        registration = DataElementRegistry.require(category);
        explicitSource = elementMatch instanceof DataElementMatch dataElementMatch
                ? dataElementMatch.explicitSource
                : supportsExplicitSource(registration.kind())
                        && hasLeadingExplicitSource(elementMatch);
    }

    public DataElementRegistration registration() {
        return registration;
    }

    public boolean hasExplicitSource() {
        return explicitSource;
    }

    public DataQuery dataQuery() {
        return compileQuery();
    }

    public static Optional<DataElementMatch> from(ElementMatch elementMatch) {
        if (elementMatch == null
                || !DataElementRegistry.contains(elementMatch.category)) {
            return Optional.empty();
        }
        if (elementMatch instanceof DataElementMatch dataElementMatch) {
            return Optional.of(dataElementMatch);
        }
        return Optional.of(new DataElementMatch(
                elementMatch.parentPhrase,
                elementMatch
        ));
    }

    private DataQuery compileQuery() {
        DataQuery.Builder builder = DataQuery.builder(
                registration.kind(),
                registration.form()
        );

        String modifier = normalize(selectionType);
        String position = normalize(elementPosition);
        switch (modifier) {
            case "" -> applyPosition(builder, position, false);
            case "every" -> {
                builder.every();
                applyPosition(builder, position, true);
            }
            case "any" -> {
                builder.any();
                applyPosition(builder, position, true);
            }
            case "none" -> throw new IllegalArgumentException(
                    "'none' is not supported for Data Elements"
            );
            default -> throw new IllegalArgumentException(
                    "Unknown Data Element selection modifier: " + selectionType
            );
        }

        int predicateStart = explicitSource ? 1 : 0;
        if (registration.kind() != DataElementKind.DATA_TABLE
                && registration.kind() != DataElementKind.DATA_DOC_STRING) {
            for (int i = predicateStart; i < textOps.size(); i++) {
                TextOp textOp = textOps.get(i);
                builder.predicate(
                        new tools.dscode.common.dataoperations.TextOp(
                                textOp.text(),
                                textOp.op()
                        )
                );
            }
        }

        DataAttribute comparisonAttribute = null;
        for (Attribute attribute : attributes) {
            Optional<DataAttribute> resolved =
                    resolveAttribute(attribute.attrName);
            if (resolved.isEmpty()) {
                continue;
            }
            if (comparisonAttribute != null
                    && comparisonAttribute != resolved.get()) {
                throw new IllegalArgumentException(
                        "A Data Element query can use only one comparison "
                                + "attribute. Found "
                                + comparisonAttribute.name().toLowerCase(Locale.ROOT)
                                + " and "
                                + resolved.get().name().toLowerCase(Locale.ROOT)
                                + "."
                );
            }
            comparisonAttribute = resolved.get();
            if (attribute.predicateVal != null) {
                builder.predicate(new tools.dscode.common.dataoperations.TextOp(
                        attribute.predicateVal,
                        attribute.predicateType
                ));
            }
        }
        if (comparisonAttribute != null) {
            builder.comparisonAttribute(comparisonAttribute);
        }

        resolveReturnAttribute().ifPresent(builder::returnAttribute);
        builder.resultUse(resolveResultUse(modifier));
        return builder.build();
    }

    private Optional<DataAttribute> resolveReturnAttribute() {
        if (valueTypes == null) {
            return Optional.empty();
        }
        for (String valueType : valueTypes) {
            Optional<DataAttribute> attribute = resolveAttribute(valueType);
            if (attribute.isPresent()) {
                return attribute;
            }
        }
        return Optional.empty();
    }

    private DataResultUse resolveResultUse(String modifier) {
        boolean context = parentPhrase != null
                && parentPhrase.phraseType == PhraseData.PhraseType.CONTEXT;
        if (!context) {
            return DataResultUse.TERMINAL;
        }
        return modifier.equals("every") || modifier.equals("any")
                ? DataResultUse.ITERATION
                : DataResultUse.CONTEXT;
    }

    private static boolean supportsExplicitSource(DataElementKind kind) {
        return kind == DataElementKind.DATA_TABLE
                || kind.group() != DataElementGroup.CUCUMBER;
    }

    private static boolean hasLeadingExplicitSource(ElementMatch elementMatch) {
        if (elementMatch.defaultText == null
                || elementMatch.defaultText.isNullOrBlank()
                || elementMatch.fullText == null
                || elementMatch.category == null) {
            return false;
        }

        int sourceIndex = elementMatch.fullText.indexOf(
                elementMatch.defaultText.toString()
        );
        int categoryIndex = elementMatch.fullText.indexOf(elementMatch.category);
        return sourceIndex >= 0
                && categoryIndex >= 0
                && sourceIndex < categoryIndex;
    }

    private static void applyPosition(
            DataQuery.Builder builder,
            String position,
            boolean stride
    ) {
        if (position.isBlank()) {
            return;
        }
        if (position.equals("last")) {
            if (stride) {
                throw new IllegalArgumentException(
                        "'last' cannot be combined with every or any"
                );
            }
            builder.last();
            return;
        }
        if (position.equals("first")) {
            if (stride) {
                throw new IllegalArgumentException(
                        "'first' cannot be combined with every or any"
                );
            }
            builder.first();
            return;
        }
        int numericPosition;
        try {
            numericPosition = Integer.parseInt(
                    position.replaceFirst("(?i)(st|nd|rd|th)$", "")
            );
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Unknown Data Element position: " + position,
                    exception
            );
        }
        if (stride) {
            builder.stride(numericPosition);
        } else {
            builder.ordinal(numericPosition);
        }
    }

    private static Optional<DataAttribute> resolveAttribute(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim()
                .replaceAll("^[\"'`]|[\"'`]$", "")
                .replaceFirst("(?i)\\s+attribute$", "")
                .trim();
        return DataAttribute.fromName(normalized);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
