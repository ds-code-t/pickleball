package tools.dscode.studio.language;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

final class JavaSourceParser {

    SourceOutline parse(String path, String content) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Java source navigation requires a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject source = new StringJavaFileObject(content);
        JavacTask task = (JavacTask) compiler.getTask(
                null,
                null,
                diagnostics,
                List.of("-proc:none"),
                null,
                List.of(source)
        );

        List<SourceSymbol> symbols = new ArrayList<>();
        try {
            for (CompilationUnitTree unit : task.parse()) {
                Trees trees = Trees.instance(task);
                new SymbolScanner(path, unit, trees.getSourcePositions(), symbols).scan(unit, null);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to parse Java source: " + path, error);
        }

        List<SourceDiagnostic> parsedDiagnostics = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() != Diagnostic.Kind.OTHER)
                .map(diagnostic -> new SourceDiagnostic(
                        diagnostic.getKind().name(),
                        diagnostic.getMessage(Locale.ROOT),
                        positive(diagnostic.getLineNumber()),
                        positive(diagnostic.getColumnNumber())
                ))
                .toList();

        return new SourceOutline(path, SourceLanguage.JAVA, symbols, parsedDiagnostics);
    }

    private static Integer positive(long value) {
        return value > 0 ? Math.toIntExact(value) : null;
    }

    private static final class SymbolScanner extends TreePathScanner<Void, Void> {
        private final String path;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final List<SourceSymbol> symbols;
        private final Deque<String> typeNames = new ArrayDeque<>();
        private final String packageName;

        private SymbolScanner(
                String path,
                CompilationUnitTree unit,
                SourcePositions positions,
                List<SourceSymbol> symbols
        ) {
            this.path = path;
            this.unit = unit;
            this.positions = positions;
            this.symbols = symbols;
            this.packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            String name = tree.getSimpleName().toString();
            if (name.isEmpty()) {
                return super.visitClass(tree, unused);
            }

            String container = currentType();
            String qualifiedName = qualify(name);
            symbols.add(symbol(
                    typeKind(tree.getKind()),
                    name,
                    qualifiedName,
                    container,
                    tree
            ));

            typeNames.addLast(name);
            try {
                return super.visitClass(tree, unused);
            } finally {
                typeNames.removeLast();
            }
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            String container = currentType();
            if (container != null) {
                boolean constructor = tree.getReturnType() == null;
                String name = constructor ? typeNames.getLast() : tree.getName().toString();
                String signature = tree.getParameters().stream()
                        .map(parameter -> parameter.getType().toString())
                        .collect(Collectors.joining(","));
                symbols.add(symbol(
                        constructor ? SourceSymbolKind.JAVA_CONSTRUCTOR : SourceSymbolKind.JAVA_METHOD,
                        name,
                        container + "#" + name + "(" + signature + ")",
                        container,
                        tree
                ));
            }
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            if (getCurrentPath().getParentPath() != null
                    && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
                String container = currentType();
                if (container != null) {
                    String name = tree.getName().toString();
                    symbols.add(symbol(
                            SourceSymbolKind.JAVA_FIELD,
                            name,
                            container + "#" + name,
                            container,
                            tree
                    ));
                }
            }
            return super.visitVariable(tree, unused);
        }

        private SourceSymbol symbol(
                SourceSymbolKind kind,
                String name,
                String qualifiedName,
                String container,
                Tree tree
        ) {
            long start = positions.getStartPosition(unit, tree);
            int line = start >= 0 ? (int) unit.getLineMap().getLineNumber(start) : 1;
            int column = start >= 0 ? (int) unit.getLineMap().getColumnNumber(start) : 1;
            return new SourceSymbol(
                    SourceLanguage.JAVA,
                    kind,
                    name,
                    qualifiedName,
                    container,
                    new SourceLocation(path, line, column)
            );
        }

        private String qualify(String name) {
            String nested = typeNames.isEmpty()
                    ? name
                    : String.join(".", typeNames) + "." + name;
            return packageName.isEmpty() ? nested : packageName + "." + nested;
        }

        private String currentType() {
            if (typeNames.isEmpty()) {
                return null;
            }
            String nested = String.join(".", typeNames);
            return packageName.isEmpty() ? nested : packageName + "." + nested;
        }

        private static SourceSymbolKind typeKind(Tree.Kind kind) {
            return switch (kind) {
                case INTERFACE -> SourceSymbolKind.JAVA_INTERFACE;
                case ENUM -> SourceSymbolKind.JAVA_ENUM;
                case RECORD -> SourceSymbolKind.JAVA_RECORD;
                case ANNOTATION_TYPE -> SourceSymbolKind.JAVA_ANNOTATION;
                default -> SourceSymbolKind.JAVA_CLASS;
            };
        }
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String content;

        private StringJavaFileObject(String content) {
            super(URI.create("string:///StudioSource.java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
