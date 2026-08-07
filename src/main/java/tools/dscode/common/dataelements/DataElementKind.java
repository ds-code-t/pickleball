package tools.dscode.common.dataelements;

import java.util.List;

import static tools.dscode.common.dataelements.DataElementForm.PLURAL;
import static tools.dscode.common.dataelements.DataElementForm.SINGULAR;
import static tools.dscode.common.dataelements.DataElementGroup.CUCUMBER;
import static tools.dscode.common.dataelements.DataElementGroup.FORMAT;
import static tools.dscode.common.dataelements.DataElementGroup.JAVA;

public enum DataElementKind {
    DATA_TABLE(CUCUMBER, "Data Table", "Data Tables"),
    DATA_ROW(CUCUMBER, "Data Row", "Data Rows"),
    DATA_COLUMN(CUCUMBER, "Data Column", "Data Columns"),
    DATA_LIST(CUCUMBER, "Data List", "Data Lists"),
    DATA_COLUMN_LIST(CUCUMBER, "Data Column List", "Data Column Lists"),
    DATA_CELL(CUCUMBER, "Data Cell", "Data Cells"),
    DATA_ENTRY(CUCUMBER, "Data Entry", "Data Entries"),
    DATA_HEADER(CUCUMBER, "Data Header", "Data Headers"),
    DATA_VALUE(CUCUMBER, "Data Value", "Data Values"),
    DATA_DOC_STRING(
            CUCUMBER,
            "Data Doc String",
            "Data Doc Strings",
            new Alias("Doc String", SINGULAR),
            new Alias("Doc Strings", PLURAL)
    ),

    MAP(JAVA, "Map", "Maps"),
    LIST(JAVA, "List", "Lists"),
    SET(JAVA, "Set", "Sets"),
    MULTIMAP(JAVA, "Multimap", "Multimaps"),

    STRUCTURED_DATA(
            FORMAT,
            "Structured Data",
            null,
            new Alias("Data", SINGULAR),
            new Alias("Data Object", SINGULAR),
            new Alias("Data Objects", PLURAL)
    ),
    JSON_DATA(FORMAT, "JSON Data", null),
    YAML_DATA(FORMAT, "YAML Data", null),
    XML_DATA(FORMAT, "XML Data", null),
    DATA_STRING(FORMAT, "Data String", "Data Strings"),
    JSON_STRING(FORMAT, "JSON String", "JSON Strings"),
    YAML_STRING(FORMAT, "YAML String", "YAML Strings"),
    XML_STRING(FORMAT, "XML String", "XML Strings");

    private final DataElementGroup group;
    private final String singularName;
    private final String pluralName;
    private final List<Alias> aliases;

    DataElementKind(
            DataElementGroup group,
            String singularName,
            String pluralName,
            Alias... aliases
    ) {
        this.group = group;
        this.singularName = singularName;
        this.pluralName = pluralName;
        this.aliases = List.of(aliases);
    }

    public DataElementGroup group() {
        return group;
    }

    public String singularName() {
        return singularName;
    }

    public String pluralName() {
        return pluralName;
    }

    public List<Alias> aliases() {
        return aliases;
    }

    public String canonicalName(DataElementForm form) {
        String name = form == SINGULAR ? singularName : pluralName;
        if (name == null) {
            throw new IllegalArgumentException(
                    this + " does not define a " + form.name().toLowerCase() + " name"
            );
        }
        return name;
    }

    public record Alias(String name, DataElementForm form) {
    }
}
