package org.telegram.messenger;
import java.util.ArrayList;import java.util.HashMap;
public final class NotificationCenter {
 public static final int messagesDeleted=1,replaceMessagesObjects=2,chatInfoDidLoad=3,updateInterfaces=4,appDidLogout=5,activeAccountChanged=6;
 public interface NotificationCenterDelegate {void didReceivedNotification(int id,int account,Object...args);}
 private static final NotificationCenter[] values={new NotificationCenter(),new NotificationCenter(),new NotificationCenter(),new NotificationCenter()};
 private static final NotificationCenter global=new NotificationCenter();
 private final HashMap<Integer,ArrayList<NotificationCenterDelegate>> listeners=new HashMap<>();
 public static NotificationCenter getInstance(int a){return values[a];}public static NotificationCenter getGlobalInstance(){return global;}
 public void addObserver(NotificationCenterDelegate d,int id){listeners.computeIfAbsent(id,k->new ArrayList<>()).add(d);}
 public void post(int id,int account,Object...args){for(NotificationCenterDelegate d:new ArrayList<>(listeners.getOrDefault(id,new ArrayList<>())))d.didReceivedNotification(id,account,args);}
}
