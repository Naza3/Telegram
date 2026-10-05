package android.os;
public final class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public boolean post(Runnable task) { looper.executor.execute(task); return true; }
}
