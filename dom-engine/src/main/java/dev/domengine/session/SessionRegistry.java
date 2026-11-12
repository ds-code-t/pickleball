package dev.domengine.session;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Named sessions owned by this CLI process. */
public final class SessionRegistry {
    private static final SessionRegistry INSTANCE = new SessionRegistry();
    private final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    public static SessionRegistry get() {
        return INSTANCE;
    }

    public static void resetForTests() {
        INSTANCE.sessions.values().forEach(BrowserSession::close);
        INSTANCE.sessions.clear();
    }

    public BrowserSession create(
            String id,
            String url,
            String cdp,
            boolean headed,
            boolean process,
            boolean acceptAttach,
            Path profile,
            List<String> allow
    ) {
        if (id == null || id.isBlank()) throw new CdpException("session id is missing");
        if (sessions.containsKey(id)) throw new CdpException("session already exists: " + id);
        BrowserSession session;
        if (cdp != null && !cdp.isBlank()) {
            if (!acceptAttach && !"1".equals(System.getenv("COMPOSITE_ATTACH"))) {
                throw new CdpException("attach requires --i-accept-local-attach or COMPOSITE_ATTACH=1");
            }
            session = BrowserSession.attach(id, cdp, process, allow == null ? List.of() : allow);
        } else {
            session = BrowserSession.launch(id, url, headed, process, profile, allow == null ? List.of() : allow);
        }
        sessions.put(id, session);
        return session;
    }

    public BrowserSession require(String id) {
        BrowserSession session = sessions.get(id);
        if (session == null || session.isClosed()) throw new CdpException("session not found: " + id);
        return session;
    }

    public ObjectNode list() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        ArrayNode array = node.putArray("sessions");
        for (BrowserSession session : sessions.values()) {
            if (!session.isClosed()) array.add(session.record());
        }
        return node;
    }

    public ObjectNode close(String id) {
        BrowserSession session = sessions.remove(id);
        if (session == null) throw new CdpException("session not found: " + id);
        boolean launched = session.launched;
        session.close();
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("id", id);
        node.put("closed", true);
        node.put("killed", launched && session.killedBrowser());
        return node;
    }
}
