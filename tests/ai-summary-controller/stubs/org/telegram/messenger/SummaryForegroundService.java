package org.telegram.messenger;
import java.util.HashMap;import java.util.ArrayList;
public final class SummaryForegroundService {
 public interface FailureCallback {void onFailure(String message);}
 public static final class Task {public final Runnable cancel;public final FailureCallback failure;Task(Runnable c,FailureCallback f){cancel=c;failure=f;}}
 public static final HashMap<String,Task> active=new HashMap<>();public static final ArrayList<String> stopped=new ArrayList<>();public static boolean rejectStart;public static int starts,updates;
 public static boolean start(int a,long o,String id,Runnable cancel){return start(a,o,id,cancel,message->{});}
 public static boolean start(int a,long o,String id,Runnable cancel,FailureCallback failure){starts++;if(rejectStart)return false;active.put(id,new Task(cancel,failure));return true;}
 public static void update(int a,long o,String id,String stage,int completed,int total){updates++;}
 public static void stop(int a,long o,String id){stopped.add(id);active.remove(id);}
 public static void stopOwner(int a,long o){active.clear();}
}
