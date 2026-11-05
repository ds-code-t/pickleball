package tools.dscode.common.composite;

/**
 * Predicate results already known for one element.
 * A state check reads this record. It does not search the document again.
 */
public record ElementState(
        boolean disabled,
        boolean blank,
        boolean required,
        boolean expanded,
        boolean attributeOn,
        boolean selectable,
        boolean domSelected
) {
}
