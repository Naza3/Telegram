package org.telegram.messenger.ai;
import java.util.ArrayList;import org.telegram.tgnet.TLRPC;
public final class SummaryHistoryLoader {
 public interface Callback {void onLoaded(Result r);void onError(String e);void onProgress(int scanned,int count);}
 public static final class Result {public final ArrayList<SummaryMessage> messages;public String coverageNote="Synthetic coverage";public int scannedMessageCount,coveredThroughId,lowerExclusiveId;public boolean complete=true;
  public boolean selectedSnapshot;public int selectedAccount;public long selectedOwnerId,selectedDialogId,selectedTopicId;
  public Result(ArrayList<SummaryMessage> m,int lower,int upper,boolean full){messages=m;scannedMessageCount=m.size();lowerExclusiveId=lower;coveredThroughId=upper;complete=full;}
  public boolean matchesSelectedScope(int account,long owner,long dialog,long topic){return selectedSnapshot&&account==selectedAccount&&owner==selectedOwnerId&&dialog==selectedDialogId&&topic==selectedTopicId;}}
 public static final ArrayList<SummaryHistoryLoader> created=new ArrayList<>();public Callback callback;public boolean cancelled;public String method;
 public int dateYear,dateMonth,dateDay;
 public void loadDate(int year,int month,int day,Callback c){callback=c;method="date";dateYear=year;dateMonth=month;dateDay=day;}
 public SummaryHistoryLoader(int a,long d,long t){created.add(this);}public void loadToday(Callback c){callback=c;method="today";}public void loadUnread(int l,int u,int n,Callback c){callback=c;method="unread";}public void loadRange(int l,int u,int n,Callback c){callback=c;method="range";}public void loadSince(int l,int n,Callback c){callback=c;method="since";}public void loadRecent(int n,Callback c){callback=c;method="recent";}public void cancel(){cancelled=true;}
 public static boolean isUsableText(TLRPC.Message m,int now){return m!=null&&m.usable&&m.message!=null;}
}
