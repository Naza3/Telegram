package org.telegram.ui.Components;
import java.util.ArrayList;import org.telegram.messenger.ai.*;
public final class GroupSummarySheet {
 enum RangeMode {RECENT,TODAY,SINCE,UNREAD,REPLAY}
 static final class RangeRequest {
  final RangeMode mode;final int count,expectedCursor,lower,upper;final boolean initialize,includePublished;final SummaryHistoryLoader.Result replayHistory;final ArrayList<SummaryMessage> replayMessages;final SummaryFilter.Options filters;
  RangeRequest(RangeMode mode,int count,int expectedCursor,int lower,int upper,boolean initialize,SummaryHistoryLoader.Result history,ArrayList<SummaryMessage> messages,SummaryFilter.Options filters){this(mode,count,expectedCursor,lower,upper,initialize,history,messages,filters,false);}
  RangeRequest(RangeMode mode,int count,int expectedCursor,int lower,int upper,boolean initialize,SummaryHistoryLoader.Result history,ArrayList<SummaryMessage> messages,SummaryFilter.Options filters,boolean includePublished){this.mode=mode;this.count=count;this.expectedCursor=expectedCursor;this.lower=lower;this.upper=upper;this.initialize=initialize;replayHistory=history;replayMessages=messages;this.filters=filters;this.includePublished=includePublished;}
 }
}
