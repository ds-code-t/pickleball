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

    sealed interface Expr permits Stage, Not, And, Or, Seq, Group, Rel {
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

    /** Relation scaffolding. {@code candidates} is null for filter form; stage form keeps the CSS (blank becomes {@code *}). */
    record Rel(RelationTerm term, String candidates) implements Expr {
    }

    static boolean hasOperator(String text) {
        if (text == null || text.isBlank()) return false;
        for (Tok token : tokenize(text)) {
            if (token.kind == Kind.AND || token.kind == Kind.OR || token.kind == Kind.NOT) return true;
        }
        return false;
    }

    static Expr parse(String text) {
        return parse(text, false);
    }

    static String render(Expr expr) {
        return switch (expr) {
            case Stage stage -> stage.engine + "=" + stage.expr;
            case Not not -> "NOT " + render(not.inner);
            case And and -> render(and.left) + " AND " + render(and.right);
            case Or or -> render(or.left) + " OR " + render(or.right);
            case Seq seq -> render(seq.left) + " >> " + render(seq.right);
            case Group group -> "(" + render(group.inner) + ")";
            case Rel rel -> rel.candidates == null ? rel.term.canonical() : renderStage(rel);
        };
    }

    static List<RelationTerm> relationTerms(String text) {
        String source = text == null ? "" : text;
        try {
            Expr expr = parse(source, true);
            List<RelationTerm> terms = new ArrayList<>();
            collect(expr, "", terms);
            return List.copyOf(terms);
        } catch (CdpException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("invalid relation:")) throw ex;
            if (looksLikeRelationAttempt(source)) throw new CdpException("invalid relation: " + source.trim());
            throw ex;
        }
    }

    static RelationTerm parseFilterTerm(String term) {
        String text = term == null ? "" : term.trim();
        try {
            Expr expr = parse(text, true);
            if (expr instanceof Rel rel && rel.candidates == null) return rel.term;
        } catch (CdpException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("invalid relation:")) throw ex;
        }
        throw new CdpException("invalid relation: " + text);
    }

    private static Expr parse(String text, boolean relations) {
        Parser parser = new Parser(text, tokenize(text), relations);
        Expr expr = parser.parsePipeline();
        parser.end();
        return expr;
    }

    static Query.Hit eval(Expr expr, List<DomNode> scope, DomNode root, String pierce) {
        return switch (expr) {
            case Stage stage -> {
                Query.Hit hit = Engines.apply(stage.engine, stage.expr, scope, root, pierce);
                yield new Query.Hit(QueryHooks.candidates(stage, hit.nodes), hit.why, hit.image);
            }
            case Group group -> eval(group.inner, scope, root, pierce);
            case Not not -> negate(not, scope, root, pierce);
            case And and -> intersect(and, scope, root, pierce);
            case Or or -> union(or, scope, root, pierce);
            case Seq seq -> sequence(seq, scope, root, pierce);
            case Rel rel -> evaluateRelation(rel, scope, root, pierce);
        };
    }

    private static Query.Hit evaluateRelation(Rel rel, List<DomNode> scope, DomNode root, String pierce) {
        if (rel.candidates == null) return Relations.filter(rel.term, scope, root, pierce);
        QueryHooks.note(scope);
        if (rel.term.negated()) return Relations.negativeStage(rel.term, rel.candidates, scope, root, pierce);
        return Engines.apply(rel.term.relation().engine(), rel.candidates, scope, root, pierce);
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
        return new Query.Hit(distinct, prefer("and hit " + distinct.size(), left.why, right.why), left.image != null ? left.image : right.image);
    }

    private static Query.Hit union(Or or, List<DomNode> scope, DomNode root, String pierce) {
        Query.Hit left = eval(or.left, scope, root, pierce);
        Query.Hit right = eval(or.right, scope, root, pierce);
        List<DomNode> kept = new ArrayList<>(left.nodes);
        kept.addAll(right.nodes);
        List<DomNode> distinct = Query.distinct(kept);
        EngineLog.info("expr", "or left=" + left.nodes.size() + " right=" + right.nodes.size() + " hit=" + distinct.size());
        return new Query.Hit(distinct, prefer("or hit " + distinct.size(), left.why, right.why), left.image != null ? left.image : right.image);
    }

    private static Query.Hit sequence(Seq seq, List<DomNode> scope, DomNode root, String pierce) {
        Query.Hit left = eval(seq.left, scope, root, pierce);
        Query.Hit right = eval(seq.right, left.nodes, root, pierce);
        EngineLog.info("expr", "seq hit=" + right.nodes.size());
        return new Query.Hit(right.nodes, prefer(right.why, left.why), right.image != null ? right.image : left.image);
    }

    private static String prefer(String outer, String... inners) {
        if (outer != null && outer.contains("anchor matched 0")) return outer;
        for (String inner : inners) {
            if (inner != null && inner.contains("anchor matched 0")) return inner;
        }
        return outer;
    }

    private static String renderStage(Rel rel) {
        StringBuilder text = new StringBuilder();
        if (rel.term.negated()) text.append("not-");
        text.append(rel.term.relation().engine());
        if (Double.compare(rel.term.weight(), RelationTerm.DEFAULT_WEIGHT) != 0) {
            text.append('@').append(RelationTerm.formatWeight(rel.term.weight()));
        }
        text.append('=').append(rel.candidates);
        return text.toString();
    }

    private static String canonicalAnchor(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return "";
        try {
            return render(parse(trimmed, true));
        } catch (CdpException ignored) {
            return trimmed;
        }
    }

    private static void collect(Expr expr, String prefix, List<RelationTerm> out) {
        switch (expr) {
            case Rel rel -> {
                if (rel.candidates == null) out.add(rel.term);
                else out.add(new RelationTerm(rel.term.relation(), rel.term.negated(), prefix, rel.term.weight()));
            }
            case Seq seq -> {
                collect(seq.left, prefix, out);
                String left = render(seq.left);
                String next = prefix.isEmpty() ? left : prefix + " >> " + left;
                collect(seq.right, next, out);
            }
            case Group group -> collect(group.inner, prefix, out);
            case Not not -> collect(not.inner, prefix, out);
            case And and -> {
                collect(and.left, prefix, out);
                collect(and.right, prefix, out);
            }
            case Or or -> {
                collect(or.left, prefix, out);
                collect(or.right, prefix, out);
            }
            case Stage ignored -> {
            }
        }
    }

    private static boolean looksLikeRelationAttempt(String source) {
        String text = source == null ? "" : source.trim();
        if (text.isEmpty()) return false;
        if (text.regionMatches(true, 0, "NOT", 0, 3) && (text.length() == 3 || Character.isWhitespace(text.charAt(3)))) {
            text = text.substring(3).trim();
        }
        if (text.regionMatches(true, 0, "not-", 0, 4)) text = text.substring(4);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || c == '-') i++;
            else break;
        }
        if (i == 0) return false;
        String word = text.substring(0, i).toLowerCase(Locale.ROOT);
        if (word.startsWith("near")) return true;
        return RelationTerm.Relation.fromEngine(word).isPresent();
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

    /** Read one stage, keeping CSS or XPath parentheses and bracket predicates inside it. */
    private static int readStage(String text, int i) {
        int n = text.length();
        boolean quote = false;
        char quoteChar = 0;
        int brackets = 0;
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
            if (c == '[') {
                brackets++;
                i++;
                continue;
            }
            if (c == ']' && brackets > 0) {
                brackets--;
                i++;
                continue;
            }
            if (c == ')') return i;
            if (c == '>' && i + 1 < n && text.charAt(i + 1) == '>') return i;
            if (brackets == 0 && (isOpAt(text, i, "AND") || isOpAt(text, i, "OR") || isOpAt(text, i, "NOT"))) return i;
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
        if (!text.regionMatches(false, i, op, 0, op.length())) warnLowercase(op);
        return true;
    }

    private static final Set<String> LOWERCASE_OPERATORS = new HashSet<>();

    static void resetOperatorWarningsForTests() {
        LOWERCASE_OPERATORS.clear();
    }

    private static void warnLowercase(String op) {
        if (!LOWERCASE_OPERATORS.add(op)) return;
        EngineLog.warn("expr", "lowercase operator '" + op.toLowerCase(Locale.ROOT) + "' read as " + op + "; write capitals");
    }

    private static final class Parser {
        private final String source;
        private final List<Tok> tokens;
        private final boolean relations;
        private int index;

        private Parser(String source, List<Tok> tokens, boolean relations) {
            this.source = source == null ? "" : source;
            this.tokens = tokens;
            this.relations = relations;
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
                Expr inner = parseNot();
                if (inner instanceof Rel rel) {
                    if (rel.term.negated()) throw new CdpException("invalid relation: " + source.trim());
                    return new Rel(rel.term.negate(), rel.candidates);
                }
                return new Not(inner);
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
            Expr rel = relationStage(piece);
            if (rel != null) return rel;
            int eq = piece.indexOf('=');
            if (eq <= 0) throw new CdpException("invalid pipeline stage: " + piece);
            int nameEnd = eq;
            char prev = piece.charAt(eq - 1);
            if (prev == '~' || prev == '^' || prev == '$') nameEnd = eq - 1;
            String engine = Query.canonicalEngine(piece.substring(0, nameEnd).trim().toLowerCase(Locale.ROOT));
            String expr = nameEnd < eq ? piece.substring(nameEnd).trim() : piece.substring(eq + 1).trim();
            if (!Query.ENGINES.contains(engine)) throw new CdpException("invalid engine: " + engine);
            return new Stage(engine, expr);
        }

        private Expr relationStage(String piece) {
            int i = 0;
            boolean negated = false;
            if (piece.regionMatches(true, 0, "not-", 0, 4)) {
                negated = true;
                i = 4;
            }
            int nameEnd = relationNameEnd(piece, i);
            if (nameEnd < 0) return null;
            RelationTerm.Relation relation = RelationTerm.Relation.fromEngine(piece.substring(i, nameEnd))
                    .orElseThrow(() -> new CdpException("invalid relation: " + piece));
            i = nameEnd;
            double weight = RelationTerm.DEFAULT_WEIGHT;
            if (i < piece.length() && piece.charAt(i) == '@') {
                int end = weightEnd(piece, i + 1);
                weight = Double.parseDouble(piece.substring(i + 1, end));
                if (!Double.isFinite(weight) || weight < 0) throw new CdpException("invalid relation: " + piece);
                i = end;
            }
            if (i >= piece.length()) throw new CdpException("invalid relation: " + piece);
            char sigil = piece.charAt(i);
            if (sigil == '(') {
                int close = closingParen(piece, i);
                if (!piece.substring(close + 1).trim().isEmpty()) throw new CdpException("invalid relation: " + piece);
                String anchor = canonicalAnchor(piece.substring(i + 1, close));
                return new Rel(new RelationTerm(relation, negated, anchor, weight), null);
            }
            if (sigil == '=') {
                String candidates = piece.substring(i + 1).trim();
                if (candidates.isEmpty()) candidates = "*";
                return new Rel(new RelationTerm(relation, negated, "", weight), candidates);
            }
            throw new CdpException("invalid relation: " + piece);
        }

        private static int relationNameEnd(String text, int start) {
            String[] names = {
                    "left-of", "right-of", "inside", "before", "above", "below", "after", "right", "near", "left"
            };
            for (String name : names) {
                if (!text.regionMatches(true, start, name, 0, name.length())) continue;
                int end = start + name.length();
                if (end >= text.length()) return end;
                char next = text.charAt(end);
                if (next == '@' || next == '(' || next == '=' || Character.isWhitespace(next)) return end;
            }
            return -1;
        }

        private static int weightEnd(String piece, int start) {
            int j = start;
            int n = piece.length();
            if (j < n && piece.charAt(j) == '-') throw new CdpException("invalid relation: " + piece);
            boolean dotted = false;
            boolean digits = false;
            if (j < n && piece.charAt(j) == '.') {
                dotted = true;
                j++;
            }
            while (j < n && Character.isDigit(piece.charAt(j))) {
                digits = true;
                j++;
            }
            if (!dotted && j < n && piece.charAt(j) == '.') {
                int fraction = ++j;
                while (j < n && Character.isDigit(piece.charAt(j))) j++;
                if (j == fraction) throw new CdpException("invalid relation: " + piece);
                digits = true;
            }
            if (!digits) throw new CdpException("invalid relation: " + piece);
            return j;
        }

        private static int closingParen(String text, int open) {
            int depth = 0;
            boolean quote = false;
            char quoteChar = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote) {
                    if (c == quoteChar) quote = false;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    quote = true;
                    quoteChar = c;
                    continue;
                }
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            throw new CdpException("invalid relation: " + text);
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
