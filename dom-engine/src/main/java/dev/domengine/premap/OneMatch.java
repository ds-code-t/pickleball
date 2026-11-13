package dev.domengine.premap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The single place that turns a candidate list into one node, or an explainable error. */
public final class OneMatch {
    private OneMatch() {
    }

    /**
     * @param negativesFilter drop candidates whose negated relations are satisfied (default true for act verbs)
     * @param actVerb         keep interactable nodes only
     * @param margin          top score must lead the second by at least this much
     * @param targetsOnly     apply box caps and the always-excluded target list
     * @param exemptRoles     roles the query asked for, so caps do not apply
     * @param sectionClause   a section clause exempts every candidate from caps
     */
    public record Policy(
            boolean negativesFilter,
            boolean actVerb,
            double margin,
            boolean targetsOnly,
            Set<String> exemptRoles,
            boolean sectionClause
    ) {
        public Policy {
            exemptRoles = exemptRoles == null ? Set.of() : Set.copyOf(exemptRoles);
            if (margin < 0) margin = 0;
        }

        public static Policy act(double margin, Set<String> exemptRoles) {
            return new Policy(true, true, margin, true, exemptRoles, false);
        }

        public static Policy resolve(double margin, Set<String> exemptRoles) {
            return new Policy(false, false, margin, true, exemptRoles, false);
        }
    }

    public record Resolved(Candidate chosen, String explain, List<Candidate> candidates) {
    }

    public static Resolved resolve(List<Candidate> candidates, Policy policy) {
        Policy used = policy == null ? Policy.resolve(0.15, Set.of()) : policy;
        List<Candidate> source = candidates == null ? List.of() : candidates;
        if (source.isEmpty()) throw new OneMatchException("not found", List.of());
        List<Candidate> rejected = new ArrayList<>();
        List<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : source) {
            String reason = reject(candidate, used);
            if (reason == null) kept.add(candidate);
            else rejected.add(candidate.withReason(reason));
        }
        if (kept.isEmpty()) throw new OneMatchException("not found", rejected);
        if (kept.size() == 1) {
            Candidate chosen = kept.get(0).withReason("kept");
            List<Candidate> all = new ArrayList<>(rejected);
            all.add(chosen);
            return new Resolved(chosen, "kept", all);
        }
        boolean scored = kept.stream().allMatch(candidate -> candidate.score() != null);
        if (scored) {
            List<Candidate> ranked = new ArrayList<>(kept);
            ranked.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.score()).reversed());
            double top = ranked.get(0).score();
            double second = ranked.get(1).score();
            if (top - second >= used.margin()) {
                String explain = String.format(Locale.ROOT, "chosen by score margin %.2f", top - second);
                Candidate chosen = ranked.get(0).withReason(explain);
                List<Candidate> all = new ArrayList<>(rejected);
                all.add(chosen);
                for (int i = 1; i < ranked.size(); i++) all.add(ranked.get(i).withReason("kept"));
                return new Resolved(chosen, explain, all);
            }
            String tie = String.format(Locale.ROOT, "tie: %.2f vs %.2f < margin %.2f", top, second, used.margin());
            List<Candidate> all = new ArrayList<>(rejected);
            all.add(ranked.get(0).withReason(tie));
            all.add(ranked.get(1).withReason(tie));
            for (int i = 2; i < ranked.size(); i++) all.add(ranked.get(i).withReason("kept"));
            throw new OneMatchException("ambiguous: " + kept.size() + " candidates", all);
        }
        List<Candidate> all = new ArrayList<>(rejected);
        for (Candidate candidate : kept) all.add(candidate.withReason("kept"));
        throw new OneMatchException("ambiguous: " + kept.size() + " candidates", all);
    }

    private static String reject(Candidate candidate, Policy policy) {
        if (policy.targetsOnly() && candidate.excluded()) return "rejected: not a target";
        boolean exempt = BoxCaps.exemptRole(candidate.role(), policy.exemptRoles(), policy.sectionClause());
        if (policy.targetsOnly() && candidate.capped() && !exempt) {
            return "rejected: box-cap " + candidate.capReason().replace(" > ", ">");
        }
        if (candidate.hidden()) return "rejected: hidden";
        if (policy.negativesFilter() && candidate.satisfiedNegatives() != null && !candidate.satisfiedNegatives().isEmpty()) {
            return "rejected: " + candidate.satisfiedNegatives().get(0) + " satisfied";
        }
        if (policy.actVerb() && !candidate.interactable()) return "rejected: not interactable";
        return null;
    }
}
