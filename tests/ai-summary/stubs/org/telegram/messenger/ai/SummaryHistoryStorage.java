package org.telegram.messenger.ai;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Models the AtomicFile boundary, not Android filesystem/backup integration. */
final class SummaryHistoryStorage {
    static final Map<String, String> FILES = new HashMap<>();
    static int failReads, failWrites, failDeletes;
    static int writes;
    static Runnable beforeWrite;
    static String key(int account, long owner) { return account + ":" + owner; }
    static String read(int account, long owner, int maxBytes) {
        if (failReads-- > 0) throw new IllegalStateException("读取失败");
        String value = FILES.get(key(account, owner));
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalStateException("文件过大");
        }
        return value;
    }
    static void write(int account, long owner, String value) {
        if (beforeWrite != null) beforeWrite.run();
        if (failWrites-- > 0) throw new IllegalStateException("写入失败，原子文件未提交");
        FILES.put(key(account, owner), value); writes++;
    }
    static void delete(int account, long owner) {
        if (failDeletes-- > 0) throw new IllegalStateException("删除失败");
        FILES.remove(key(account, owner));
    }
    static void reset() {
        FILES.clear(); failReads = failWrites = failDeletes = writes = 0; beforeWrite = null;
    }
}
