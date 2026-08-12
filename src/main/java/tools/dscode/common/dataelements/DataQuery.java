package tools.dscode.common.dataelements;

import tools.dscode.common.dataoperations.TextOp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class DataQuery {
    private final DataElementKind kind;
    private final DataElementForm form;
    private final DataCardinality cardinality;
    private final Integer ordinal;
    private final Integer stride;
    private final DataBoundary boundary;
    private final List<TextOp> predicates;
    private final DataAttribute comparisonAttribute;
    private final DataAttribute returnAttribute;
    private final DataResultUse resultUse;

    private DataQuery(Builder builder) {
        kind = builder.kind;
        form = builder.form;
        cardinality = builder.cardinality;
        ordinal = builder.ordinal;
        stride = builder.stride;
        boundary = builder.boundary;
        predicates = List.copyOf(builder.predicates);
        comparisonAttribute = builder.comparisonAttribute;
        returnAttribute = builder.returnAttribute;
        resultUse = builder.resultUse;
        validate();
    }

    public static Builder builder(
            DataElementKind kind,
            DataElementForm form
    ) {
        return new Builder(kind, form);
    }

    public DataElementKind kind() {
        return kind;
    }

    public DataElementForm form() {
        return form;
    }

    public DataCardinality cardinality() {
        return cardinality;
    }

    public Integer ordinal() {
        return ordinal;
    }

    public Integer stride() {
        return stride;
    }

    public DataBoundary boundary() {
        return boundary;
    }

    public List<TextOp> predicates() {
        return predicates;
    }

    public DataAttribute comparisonAttribute() {
        return comparisonAttribute;
    }

    public DataAttribute returnAttribute() {
        return returnAttribute;
    }

    public DataResultUse resultUse() {
        return resultUse;
    }

    private void validate() {
        if (ordinal != null && ordinal < 1) {
            throw new IllegalArgumentException("Ordinal must be at least 1");
        }
        if (stride != null && stride < 1) {
            throw new IllegalArgumentException("Stride must be at least 1");
        }
        if (form == DataElementForm.PLURAL && ordinal != null) {
            throw new IllegalArgumentException(
                    "A plural Data Element cannot use an explicit ordinal"
            );
        }
        if (form == DataElementForm.PLURAL
                && boundary != DataBoundary.NONE) {
            throw new IllegalArgumentException(
                    "A plural Data Element cannot use first or last"
            );
        }
        if (ordinal != null && boundary != DataBoundary.NONE) {
            throw new IllegalArgumentException(
                    "Ordinal cannot be combined with first or last"
            );
        }
        if (cardinality.many() && boundary != DataBoundary.NONE) {
            throw new IllegalArgumentException(
                    "First or last cannot be combined with a many-result query"
            );
        }
        if (cardinality.many() && ordinal != null) {
            throw new IllegalArgumentException(
                    "An ordinal cannot be combined with a many-result query"
            );
        }
        if (!cardinality.many() && stride != null) {
            throw new IllegalArgumentException(
                    "Stride requires every or any cardinality"
            );
        }
    }

    public static final class Builder {
        private final DataElementKind kind;
        private final DataElementForm form;
        private DataCardinality cardinality;
        private Integer ordinal;
        private Integer stride;
        private DataBoundary boundary = DataBoundary.NONE;
        private final List<TextOp> predicates = new ArrayList<>();
        private DataAttribute comparisonAttribute;
        private DataAttribute returnAttribute;
        private DataResultUse resultUse = DataResultUse.TERMINAL;
        private SelectionModifier selectionModifier =
                SelectionModifier.DEFAULT;

        private Builder(
                DataElementKind kind,
                DataElementForm form
        ) {
            this.kind = Objects.requireNonNull(kind);
            this.form = Objects.requireNonNull(form);
            cardinality = form == DataElementForm.SINGULAR
                    ? DataCardinality.REQUIRED_ONE
                    : DataCardinality.OPTIONAL_MANY;
        }

        public Builder every() {
            useSelectionModifier(SelectionModifier.EVERY);
            cardinality = DataCardinality.REQUIRED_MANY;
            return this;
        }

        public Builder any() {
            useSelectionModifier(SelectionModifier.ANY);
            cardinality = DataCardinality.OPTIONAL_MANY;
            return this;
        }

        public Builder ordinal(int ordinal) {
            useSelectionModifier(SelectionModifier.ORDINAL);
            this.ordinal = ordinal;
            cardinality = DataCardinality.REQUIRED_ONE;
            return this;
        }

        public Builder stride(int stride) {
            this.stride = stride;
            return this;
        }

        public Builder first() {
            useSelectionModifier(SelectionModifier.FIRST);
            boundary = DataBoundary.FIRST;
            cardinality = DataCardinality.REQUIRED_ONE;
            return this;
        }

        public Builder last() {
            useSelectionModifier(SelectionModifier.LAST);
            boundary = DataBoundary.LAST;
            cardinality = DataCardinality.REQUIRED_ONE;
            return this;
        }

        public Builder predicate(TextOp predicate) {
            predicates.add(Objects.requireNonNull(predicate));
            return this;
        }

        public Builder predicates(Collection<TextOp> predicates) {
            if (predicates != null) {
                predicates.forEach(this::predicate);
            }
            return this;
        }

        public Builder comparisonAttribute(DataAttribute attribute) {
            comparisonAttribute = attribute;
            return this;
        }

        public Builder returnAttribute(DataAttribute attribute) {
            returnAttribute = attribute;
            return this;
        }

        public Builder resultUse(DataResultUse resultUse) {
            this.resultUse = Objects.requireNonNull(resultUse);
            return this;
        }

        private void useSelectionModifier(
                SelectionModifier requested
        ) {
            if (selectionModifier != SelectionModifier.DEFAULT
                    && selectionModifier != requested) {
                throw new IllegalArgumentException(
                        "Selection modifier "
                                + requested.name().toLowerCase()
                                + " cannot be combined with "
                                + selectionModifier.name().toLowerCase()
                );
            }
            selectionModifier = requested;
        }

        public DataQuery build() {
            return new DataQuery(this);
        }

        private enum SelectionModifier {
            DEFAULT,
            EVERY,
            ANY,
            ORDINAL,
            FIRST,
            LAST
        }
    }
}
