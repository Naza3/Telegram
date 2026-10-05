package android.util;
import java.io.*;
import java.nio.file.*;
public final class AtomicFile {
    public static volatile String fault;
    private final File base;
    public AtomicFile(File base) { this.base = base; }
    public File getBaseFile() { return base; }
    public FileInputStream openRead() throws FileNotFoundException {
        File backup = new File(base + ".bak");
        if (backup.exists()) { base.delete(); backup.renameTo(base); }
        if (base.exists()) new File(base + ".new").delete();
        return new FileInputStream(base);
    }
    public FileOutputStream startWrite() throws IOException {
        if ("start".equals(fault)) throw new IOException("injected open failure");
        return new FileOutputStream(new File(base + ".new")) {
            @Override public void write(byte[] bytes) throws IOException {
                if ("write".equals(fault)) {
                    super.write(bytes, 0, Math.min(7, bytes.length));
                    throw new IOException("injected partial write failure");
                }
                super.write(bytes);
            }
        };
    }
    public void finishWrite(FileOutputStream stream) {
        try {
            stream.close();
            if ("finish".equals(fault)) throw new IllegalStateException("injected finish failure");
            if ("rename".equals(fault)) return; // Android can only log a failed rename.
            Files.move(new File(base + ".new").toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    public void failWrite(FileOutputStream stream) {
        try { stream.close(); } catch (IOException ignored) { }
        new File(base + ".new").delete();
    }
}
