package org.telegram.messenger;
import java.io.File;
public final class ApplicationLoader {
    public static final TestContext applicationContext = new TestContext();
    public static final class TestContext { public File getNoBackupFilesDir() { throw new AssertionError("Test must inject a store"); } }
}
