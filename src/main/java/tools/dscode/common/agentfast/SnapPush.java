package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import tools.dscode.browser.DomMutations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E2b push delta for {@code snap --since}. While a page socket stays open in this process (the start-host worker),
 * one DOM change listener per page counts pushed batches. A snap cursor remembers the count, so the next
 * {@code snap --since} reports how many pushed changes happened in between and where, without re-polling the page.
 * A page reached through a one-call socket has no listener; snap then says {@code delta.mode=poll}.
 */
final class SnapPush {
    private static final int KEEP = 32;
    private static final Map<CdpConnection, Feed> FEEDS = new ConcurrentHashMap<>();
    private static final Map<String, Mark> MARKS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Mark> eldest) {
            return size() > KEEP;
        }
    };

    private SnapPush() {
    }

    private record Mark(CdpConnection cdp, long count) {
    }

    private static final class Feed {
        final DomMutations.CdpWatch watch;
        final List<ObjectNode> batches = new ArrayList<>();
        long count;

        Feed(DomMutations.CdpWatch watch) {
            this.watch = watch;
        }

        synchronized void pull() {
            for (ObjectNode batch : watch.drain()) {
                count++;
                batches.add(batch);
                if (batches.size() > 200) batches.remove(0);
            }
        }

        synchronized List<ObjectNode> after(long mark) {
            pull();
            int newer = (int) Math.min(batches.size(), Math.max(0, count - mark));
            return List.copyOf(batches.subList(batches.size() - newer, batches.size()));
        }
    }

    /** Starts the listener for a long-lived page socket; false when the page refused or the socket is per-call. */
    static boolean ensure(CdpConnection cdp, boolean longLived) {
        if (cdp == null || !longLived || !PushWake.enabled()) return false;
        FEEDS.entrySet().removeIf(e -> e.getKey().isClosed());
        if (FEEDS.containsKey(cdp)) return true;
        try {
            DomMutations.CdpWatch watch = DomMutations.CdpWatch.start(cdp,
                    new DomMutations.Options("", DomMutations.KINDS, List.of(), "", 30, false));
            FEEDS.put(cdp, new Feed(watch));
            return true;
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    /** Remembers the pushed-change count at this snap cursor. */
    static synchronized void mark(String cursor, CdpConnection cdp) {
        Feed feed = cdp == null ? null : FEEDS.get(cdp);
        if (feed == null || cursor == null) return;
        feed.pull();
        MARKS.put(cursor, new Mark(cdp, feed.count));
    }

    /** {@code delta}: mode push with the pushed changes since the cursor, or mode poll. */
    static synchronized Map<String, Object> delta(String cursor, CdpConnection cdp) {
        Map<String, Object> out = new LinkedHashMap<>();
        Mark mark = cursor == null ? null : MARKS.get(cursor);
        Feed feed = cdp == null ? null : FEEDS.get(cdp);
        if (mark == null || feed == null || mark.cdp() != cdp) {
            out.put("mode", "poll");
            return out;
        }
        List<ObjectNode> batches = feed.after(mark.count());
        int records = 0;
        Set<String> targets = new LinkedHashSet<>();
        for (JsonNode batch : batches) {
            records += batch.path("records").asInt(0);
            batch.path("targets").forEach(t -> {
                if (targets.size() < 20) targets.add(t.asText());
            });
        }
        out.put("mode", "push");
        out.put("batches", batches.size());
        out.put("mutations", records);
        out.put("targets", List.copyOf(targets));
        out.put("unchanged", batches.isEmpty());
        return out;
    }
}
