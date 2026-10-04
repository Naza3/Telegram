package org.telegram.messenger;
import java.util.ArrayList;
public final class TestQueue {
 public final ArrayList<Runnable> pending=new ArrayList<>();
 public void postRunnable(Runnable r){pending.add(r);}
 public void runNext(){pending.remove(0).run();}
 public void runLast(){pending.remove(pending.size()-1).run();}
 public void drain(){int n=0;while(!pending.isEmpty()){if(++n>1000)throw new AssertionError("queue failed to quiesce");runNext();}}
 public void clear(){pending.clear();}
}
