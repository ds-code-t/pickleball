package dev.domengine.query;

import dev.domengine.cdp.CdpException;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Boolean expression layer over pipeline stages.
 * Precedence is parentheses, then NOT, then AND, then OR.
 * {@code >>} is lower and stays a sequential pipeline outside this layer,
 * except inside parentheses, where the group is one node set.
 */
final class Expressions {
    private Expressions() {
    }

    sealed interface Expr permits Stage, Not, And, Or, Seq, Group {
    }

    record Stage(String engine, String expr) implements Expr {
    }

    record Not(Expr inner) implements Expr {
    }

    record And(Expr left, Expr right) implements Expr {
    }

    record Or(Expr left, Expr right) implements Expr {
    }

    record Seq(Expr left, Expr right) implements Expr {
    }

    record Group(Expr inner) implements Expr {
    }

    static boolean hasOperator(String text) {
        if (text == null || text.isBlank()) return false;
        for (Tok token : tokenize(text)) {
            if (token.kind == Kind.AND || token.kind == Kind.OR || token.kind == Kind.NOT) return true;
        }
        return false;
    }

    static Expr parse(String text) {
        Parser parser = new Parser(text, tokenize(text));
        Expr expr = parser.parsePipeline();
        parser.end();
        return expr;
    }

    static Query.Hit eval(Expr expr, List<DomNode> scope, DomNode root, String pierce) {
        return switch (expr) {
            case Stage stage -> Engines.apply(stage.engine, stage.expr, scope, root, pierce);
            case Group group -> eval(group.inner, scope, root, pierce);
            case Not not -> negate(not, scope, root, pierce);
            case And and -> intersect(and, scope, root, pierce);
            case Or or -> union(or, scope, root, pierce);
            case Seq seq -> sequence(seq, scope, root, pierce);
        };
    }

    private static Query.Hit negate(Not not, List<DomNode> scope, DomNode root, String pierce) {
        Query.Hit inner = eval(not.inner, scope, root, pierce);
        List<DomNode> universe = Engines.scopeElements(scope, root, pierce);
        Set<String> drop = new HashSet<>();
        for (DomNode node : inner.nodes) drop.add(node.id());
        List<DomNode> kept = new ArrayList<>();
        for (DomNode node : universe) {
            if (!drop.contains(node.id())) kept.add(node);
        }
        EngineLog.info("expr", "not universe=" + universe.size() + " inner=" + inner.nodes.size() + " hit=" + kept.size());
        return new Query.Hit(kept, "not hit " + kept.size(), inner.image);
    }

    private static Query.Hit intersect(And and, List<DomNode> scope, DomNode root, String pierce) {
        Query.Hit left = eval(and.left, scope, root, pierce);
        Query.Hit right = eval(and.right, scope, root, pierce);
        Set<String> ids = new HashSet<>();
        for (DomNode node : right.nodes) ids.add(node.id());
        List<DomNode> kept = new ArrayList<>();
        for (DomNode node : left.nodes) {
            if (ids.contains(node.id())) kept.add(node);
        }
        List<DomNode> distinct = Query.distinct(kept);
        EngineLog.info("expr", "and left=" + left.nodes.size() + " right=" + right.nodes.size() + " hit=" + distinct.size());
        return new Query.Hit(distinct, "and hit " + distinct.size(), left.image != null ? left.image : right.image);
    }

    private static Query.Hit union(Or or, List<DomNode> scope, DomNode root, String pierce) {
        Query.Hit left = eval(or.left, scope, root, pierce);
        Query.Hit right = eval(or.right, scope, root, pierce);
        List<DomNode> kept = new ArrayList<>(left.nodes);
        kept.addAll(right.nodes);
        List<DomNode> distinct = Query.distinct(kept);
        EngineLog.info("expr", "or left=" + left.nodes.size() + " right=" + right.nodes.size() + " hit=" + distinct.size());
        return new Query.Hit(distinct, "or hit " + distinct.size(), left.image != null ? left.image : right.image);
    }

    private static Query.Hit sequence(Seq seq, List<DomNode> scope, DomNode root, String pierce) {
        Query.Hit left = eval(seq.left, scope, root, pierce);
        Query.Hit right = eval(seq.right, left.nodes, root, pierce);
        EngineLog.info("expr", "seq hit=" + right.nodes.size());
        return new Query.Hit(right.nodes, right.why, right.image != null ? right.image : left.image);
    }

    private enum Kind {
        STAGE, AND, OR, NOT, LP, RP, PIPE
    }

    private record Tok(Kind kind, String text) {
    }

    private static List<Tok> tokenize(String text) {
        String source = text == null ? "" : text;
        List<Tok> tokens = new ArrayList<>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                tokens.add(new Tok(Kind.LP, "("));
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(new Tok(Kind.RP, ")"));
                i++;
                continue;
            }
            if (isOpAt(source, i, "AND")) {
                tokens.add(new Tok(Kind.AND, "AND"));
                i += 3;
                continue;
            }
            if (isOpAt(source, i, "OR")) {
                tokens.add(new Tok(Kind.OR, "OR"));
                i += 2;
                continue;
            }
            if (isOpAt(source, i, "NOT")) {
                tokens.add(new Tok(Kind.NOT, "NOT"));
                i += 3;
                continue;
            }
            if (c == '>' && i + 1 < n && source.charAt(i + 1) == '>') {
                tokens.add(new Tok(Kind.PIPE, ">>"));
                i += 2;
                continue;
            }
            int start = i;
            i = readStage(source, i);
            String stage = source.substring(start, i).trim();
            if (!stage.isEmpty()) tokens.add(new Tok(Kind.STAGE, stage));
        }
        return tokens;
    }

    /** Read one stage, keeping CSS or XPath parentheses inside it. */
    private static int readStage(String text, int i) {
        int n = text.length();
        boolean quote = false;
        char quoteChar = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (quote) {
                if (c == quoteChar) quote = false;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                i++;
                continue;
            }
            if (c == '(') {
                i = skipBalanced(text, i);
                continue;
            }
            if (c == ')') return i;
            if (c == '>' && i + 1 < n && text.charAt(i + 1) == '>') return i;
            if (isOpAt(text, i, "AND") || isOpAt(text, i, "OR") || isOpAt(text, i, "NOT")) return i;
            i++;
        }
        return i;
    }

    private static int skipBalanced(String text, int i) {
        int n = text.length();
        int depth = 0;
        boolean quote = false;
        char quoteChar = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (quote) {
                if (c == quoteChar) quote = false;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                i++;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                i++;
                if (depth == 0) return i;
                continue;
            }
            i++;
        }
        throw new CdpException("invalid pipeline stage: " + text);
    }

    private static boolean isOpAt(String text, int i, String op) {
        if (!text.regionMatches(true, i, op, 0, op.length())) return false;
        if (i > 0) {
            char before = text.charAt(i - 1);
            if (!Character.isWhitespace(before) && before != '(') return false;
        }
        int after = i + op.length();
        if (after < text.length()) {
            char next = text.charAt(after);
            if (!Character.isWhitespace(next) && next != ')' && next != '(') return false;
        }
        return true;
    }

    private static final class Parser {
        private final String source;
        private final List<Tok> tokens;
        private int index;

        private Parser(String source, List<Tok> tokens) {
            this.source = source == null ? "" : source;
            this.tokens = tokens;
        }

        private Expr parsePipeline() {
            Expr left = parseOr();
            while (peek(Kind.PIPE)) {
                consume();
                left = new Seq(left, parseOr());
            }
            return left;
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            if (!peek(Kind.OR)) return left;
            if (left instanceof And) mixed();
            while (peek(Kind.OR)) {
                consume();
                Expr right = parseAnd();
                if (right instanceof And) mixed();
                left = new Or(left, right);
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseNot();
            while (peek(Kind.AND)) {
                consume();
                left = new And(left, parseNot());
            }
            return left;
        }

        private Expr parseNot() {
            if (peek(Kind.NOT)) {
                consume();
                return new Not(parseNot());
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            if (peek(Kind.LP)) {
                consume();
                Expr inner = parsePipeline();
                expect(Kind.RP);
                return new Group(inner);
            }
            if (peek(Kind.STAGE)) {
                return stage(consume().text);
            }
            throw new CdpException("invalid pipeline stage: " + source.trim());
        }

        private void end() {
            if (index < tokens.size()) throw new CdpException("invalid pipeline stage: " + source.trim());
        }

        private Expr stage(String text) {
            String piece = text.trim();
            int eq = piece.indexOf('=');
            if (eq <= 0) throw new CdpException("invalid pipeline stage: " + piece);
            String engine = piece.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String expr = piece.substring(eq + 1).trim();
            if (!Query.ENGINES.contains(engine)) throw new CdpException("invalid engine: " + engine);
            return new Stage(engine, expr);
        }

        private boolean peek(Kind kind) {
            return index < tokens.size() && tokens.get(index).kind == kind;
        }

        private Tok consume() {
            if (index >= tokens.size()) throw new CdpException("invalid pipeline stage: " + source.trim());
            return tokens.get(index++);
        }

        private void expect(Kind kind) {
            if (!peek(kind)) throw new CdpException("invalid pipeline stage: " + source.trim());
            consume();
        }

        private void mixed() {
            throw new CdpException("invalid pipeline: parentheses required for mixed AND and OR");
        }
    }
}
