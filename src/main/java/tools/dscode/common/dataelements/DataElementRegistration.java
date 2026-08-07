package tools.dscode.common.dataelements;

public record DataElementRegistration(
        DataElementKind kind,
        DataElementForm form,
        String name,
        boolean alias
) {
    public String canonicalName() {
        return kind.canonicalName(form);
    }

    public boolean plural() {
        return form == DataElementForm.PLURAL;
    }
}
