package org.telegram.messenger;
import java.util.ArrayList;
public final class AndroidUtilities {
 public static final TestQueue ui=new TestQueue(); public static final ArrayList<Runnable> delayed=new ArrayList<>();
 public static void runOnUIThread(Runnable r){ui.postRunnable(r);}
 public static void runOnUIThread(Runnable r,long delay){delayed.add(r);}
 public static void cancelRunOnUIThread(Runnable r){ui.pending.remove(r);delayed.remove(r);}
}
