package org.telegram.messenger.ai;
import java.util.ArrayList;
public final class AiSummaryClient {
 public enum Stage {SOURCE,MERGE,VERIFY} public enum RequestPhase {SENDING,WAITING,STREAMING}
 public static final class Progress {public Stage stage=Stage.SOURCE;public int completed,total=1,mergeRound;}
 public static final class RequestStatus {public RequestPhase phase=RequestPhase.SENDING;public long elapsedMs;}
 public static final class RequestInput {public int inputCharacters;public RequestInput(int n){inputCharacters=n;}}
 public interface Callback {void onProgress(Progress p);void onRequestStatus(RequestStatus s);void onRequestInput(RequestInput i);void onPartial(String s);void onSuccess(String s);void onError(String s);}
 public static final ArrayList<AiSummaryClient> created=new ArrayList<>(); public Callback callback;public boolean cancelled;public int calls;
 public AiSummaryClient(){created.add(this);}public void summarize(AiSummarySettings.Config c,ArrayList<SummaryMessage> m,PromptOptions p,Callback cb){calls++;callback=cb;}
 public void cancel(){cancelled=true;}
}
