package org.telegram.messenger.ai;
import java.util.HashSet;import java.util.Set;
/** Controlled provenance-storage boundary, not a copy of its production implementation. */
public final class SummaryPublishStore {
 public static final Set<SummaryFilter.PublishedMessageId> confirmed=new HashSet<>();public static boolean fail;
 public static Set<SummaryFilter.PublishedMessageId> confirmedMessages(int account,long owner,long dialog){if(fail)throw new IllegalStateException("Synthetic provenance failure");return new HashSet<>(confirmed);}
}
