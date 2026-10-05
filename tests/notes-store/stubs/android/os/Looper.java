package android.os;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public final class Looper {
    private static final Looper MAIN = new Looper();
    public volatile Thread thread;
    final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "test-main");
        worker.setDaemon(true);
        thread = worker;
        return worker;
    });
    public static Looper getMainLooper() { return MAIN; }
}
