package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.treeparsing.parsedComponents.ElementType;

import java.util.EnumSet;

public record OperationMeta(EnumSet<ElementType> relationalTypes, EnumSet<ElementType> requiredTypes) {

}
