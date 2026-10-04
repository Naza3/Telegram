package org.telegram.messenger.ai;
import java.util.ArrayList;import org.telegram.messenger.UserConfig;
public final class SummaryHistoryStore {
 public static final class Record {public final String id,summary,rangeLabel;public final long dialogId,topicId;public final boolean partial;public final ArrayList<SummarySourceReference> sources;
 public Record(String id,long d,long t,long time,String title,String range,String coverage,String ignored,String prompt,String model,String summary,boolean partial,ArrayList<SummarySourceReference> sources,boolean legacy){this.id=id;dialogId=d;topicId=t;this.summary=summary;this.rangeLabel=range;this.partial=partial;this.sources=sources;}}
 public static final ArrayList<Record> saved=new ArrayList<>();public static boolean fail;public static Runnable duringSave;
 public static void save(int a,long owner,Record r){if(owner!=UserConfig.getInstance(a).getClientUserId()||fail)throw new IllegalStateException("Synthetic store failure");if(duringSave!=null){Runnable run=duringSave;duringSave=null;run.run();}saved.add(r);}
}
