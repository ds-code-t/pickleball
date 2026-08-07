package tools.dscode.common.dataelements;

import java.util.List;
import java.util.Objects;

public final class DataSelection {
    private final DataContext context;
    private final DataQuery query;
    private final List<DataCandidate> candidates;
    private final int candidateCountBeforeFiltering;
    private final int filteredCandidateCount;

    DataSelection(
            DataContext context,
            DataQuery query,
            List<DataCandidate> candidates,
            int candidateCountBeforeFiltering,
            int filteredCandidateCount
    ) {
        this.context = Objects.requireNonNull(context);
        this.query = Objects.requireNonNull(query);
        this.candidates = List.copyOf(candidates);
        this.candidateCountBeforeFiltering = candidateCountBeforeFiltering;
        this.filteredCandidateCount = filteredCandidateCount;
    }

    public DataContext context() {
        return context;
    }

    public DataQuery query() {
        return query;
    }

    public List<DataCandidate> candidates() {
        return candidates;
    }

    public int size() {
        return candidates.size();
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    public DataCandidate first() {
        return candidates.getFirst();
    }

    public int candidateCountBeforeFiltering() {
        return candidateCountBeforeFiltering;
    }

    public int filteredCandidateCount() {
        return filteredCandidateCount;
    }

    public Object materializeTerminal() {
        if (candidates.isEmpty() && !query.cardinality().many()) {
            return null;
        }
        return DataMaterializer.materializeTerminal(candidates, query);
    }
}
