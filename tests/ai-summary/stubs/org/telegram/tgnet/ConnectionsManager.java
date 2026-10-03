package org.telegram.tgnet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Queued transport: tests deliver each reply explicitly after inspecting the request. */
public final class ConnectionsManager {
    public static final int RequestFlagFailOnServerErrors = 2;
    public static final int RequestFlagInvokeAfter = 64;
    private static final ConnectionsManager[] INSTANCES = new ConnectionsManager[4];
    private static int nextGuid = 10;
    public final Map<Integer, Pending> pending = new LinkedHashMap<>();
    public final List<TLObject> sent = new ArrayList<>();
    public final List<Integer> cancelledGuids = new ArrayList<>();
    private int nextRequest = 1;
    public static class Pending {
        public final TLObject request;
        public final RequestDelegate delegate;
        public int guid;
        Pending(TLObject request, RequestDelegate delegate) {
            this.request = request;
            this.delegate = delegate;
        }
    }
    public static ConnectionsManager getInstance(int account) {
        if (INSTANCES[account] == null) INSTANCES[account] = new ConnectionsManager();
        return INSTANCES[account];
    }
    public static int generateClassGuid() { return ++nextGuid; }
    public int getCurrentTime() { return (int) (System.currentTimeMillis() / 1000L); }
    public int sendRequest(TLObject request, RequestDelegate delegate) {
        return sendRequest(request, delegate, 0);
    }
    public int sendRequest(TLObject request, RequestDelegate delegate, int flags) {
        int id = nextRequest++;
        pending.put(id, new Pending(request, delegate));
        sent.add(request);
        return id;
    }
    public void bindRequestToGuid(int id, int guid) { pending.get(id).guid = guid; }
    public void cancelRequestsForGuid(int guid) {
        cancelledGuids.add(guid);
        pending.values().removeIf(p -> p.guid == guid);
    }
    public void cancelRequest(int id, boolean notifyServer) { pending.remove(id); }
    public Pending next() {
        if (pending.isEmpty()) throw new AssertionError("Expected pending Telegram request");
        return pending.values().iterator().next();
    }
    public void reply(TLObject response, TLRPC.TL_error error) {
        int id = pending.keySet().iterator().next();
        Pending call = pending.remove(id);
        call.delegate.run(response, error);
    }
    public void reset() {
        pending.clear(); sent.clear(); cancelledGuids.clear();
    }
}
