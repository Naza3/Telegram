package org.telegram.messenger;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** Runs the production service with deterministic Android boundary fakes, not a device emulator. */
public final class SummaryForegroundServiceTest {
    static int assertions, cases;
    static SummaryForegroundService service;
    static int cancelled;
    static List<String> failures = new ArrayList<>();
    static final long LIMIT = SummaryForegroundService.MAX_TASK_DURATION_MS;
    static void check(boolean condition, String message) { assertions++; if(!condition) throw new AssertionError(message); }
    static Map<?,?> tasks() throws Exception { Field f=SummaryForegroundService.class.getDeclaredField("TASKS");f.setAccessible(true);return (Map<?,?>)f.get(null); }
    static void reset() throws Exception {
        if(service!=null)service.onDestroy();
        check(tasks().isEmpty(),"prior fixture cleared all tasks");
        check(PowerManager.heldCount()==0,"prior fixture released every lock");
        Handler.reset();PowerManager.reset();NotificationManager.reset();Context.starts.clear();
        Context.rejectStart=false;Context.permission=0;Service.rejectForeground=false;
        ApplicationLoader.mainInterfacePaused=false;Build.VERSION.SDK_INT=36;Looper.isMain=true;
        for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++)UserConfig.configs[i].owner=100+i;
        cancelled=0;failures.clear();service=new SummaryForegroundService();cases++;
    }
    static boolean start(String id) { return start(0,100,id); }
    static boolean start(int account,long owner,String id) {
        return SummaryForegroundService.start(account,owner,id,()->cancelled++,failures::add);
    }
    static Intent latest() { return Context.starts.get(Context.starts.size()-1); }
    static void deliver() { check(service.onStartCommand(latest(),0,Context.starts.size())==Service.START_NOT_STICKY,"never sticky"); }
    static void stop(String id) { SummaryForegroundService.stop(0,100,id); }
    static void empty() throws Exception { check(tasks().isEmpty(),"tasks empty");check(PowerManager.heldCount()==0,"wake locks released");check(!((Service)service).foreground,"foreground released"); }

    public static void main(String[] args) throws Exception {
        reset();
        check(start("normal"),"start accepted");deliver();
        check(((Service)service).foreground,"foreground running");
        check(service.serviceType==1,"dataSync service type");
        check(PowerManager.heldCount()==1,"partial wake acquired");
        check(PowerManager.locks.get(0).timeout==LIMIT,"bounded exactly two hours");
        check(!PowerManager.locks.get(0).ref,"not reference counted");
        check(service.last.ongoing,"ongoing notification");
        check(service.last.content.intent.getAction().equals(SummaryForegroundService.ACTION_OPEN_CENTER),"AI center route");
        check(service.last.content.intent.getLongExtra(SummaryForegroundService.EXTRA_OWNER,0)==100,"owner route fence");
        check((service.last.content.flags&PendingIntent.FLAG_IMMUTABLE)!=0,"immutable content intent");
        stop("normal");empty();check(failures.isEmpty()&&cancelled==0,"normal stop no error");
        check(Handler.size()==0,"normal stop removes timers");service.onDestroy();check(failures.isEmpty(),"destroy after success quiet");

        reset();ApplicationLoader.mainInterfacePaused=true;
        check(!start("paused"),"background explicit start denied");ApplicationLoader.mainInterfacePaused=false;
        check(!start(-1,100,"bad-account"),"invalid account denied");
        check(!start(0,101,"wrong-owner"),"wrong owner denied");
        check(!start(0,100,""),"empty token denied");
        check(!start(0,100,null),"null token denied");
        check(!SummaryForegroundService.start(0,100,"null-cancel",null),"null cancel denied");
        Looper.isMain=false;check(!start("worker"),"non-main start denied");Looper.isMain=true;
        check(Context.starts.isEmpty(),"no service for invalid calls");empty();

        reset();Context.rejectStart=true;check(!start("start-rejected"),"Android12 rejection returned false");
        check(failures.isEmpty()&&cancelled==0,"caller handles immediate rejection once");
        check(Handler.size()==0,"rejection removes timers");empty();

        reset();check(start("missing-host"),"pending service starts");Handler.advance(10_000);
        check(failures.size()==1&&failures.get(0).contains("未完成"),"startup watchdog reports incomplete");
        empty();check(Handler.size()==0,"startup timeout cleans timers");deliver();empty();
        check(failures.size()==1,"late start cannot revive task");

        reset();Service.rejectForeground=true;check(start("fgs-rejected"),"dispatch accepted");deliver();
        empty();check(failures.size()==1,"foreground rejection failure");
        check(!failures.get(0).contains("sensitive"),"system exception not exposed");
        check(Handler.size()==0,"foreground rejection removes timers");

        reset();PowerManager.failAcquire=true;check(start("wake-rejected"),"wake dispatch");deliver();
        empty();check(failures.size()==1,"wake denial fails work");check(service.stopped,"wake denial stops service");

        reset();check(SummaryForegroundService.start(0,100,"system-timeout",()->cancelled++, reason->{
            check(!((Service)service).foreground&&PowerManager.heldCount()==0,"timeout releases before callback");failures.add(reason);
        }),"timeout task starts");deliver();service.onTimeout(1,1);empty();
        check(failures.size()==1&&failures.get(0).contains("未完成"),"Android15 timeout explicit incomplete");
        check(cancelled==0,"failure channel only");check(Handler.size()==0,"quota timeout clears timers");
        service.onDestroy();check(failures.size()==1,"timeout then destroy idempotent");

        reset();check(start("hard-limit"),"deadline start");deliver();Handler.advance(LIMIT);
        empty();check(failures.size()==1&&failures.get(0).contains("2 小时"),"hard deadline cancels work");
        check(Handler.size()==0,"hard deadline clears timers");

        reset();check(start("elapsed-limit"),"elapsed task start");deliver();
        SystemClock.now=LIMIT+1;Handler.uptime=1_000;Handler.drain();
        empty();check(failures.size()==1&&failures.get(0).contains("2 小时"),"elapsed clock expiry catches uptime divergence");

        reset();check(start("fenced"),"fenced start");deliver();Notification n=service.last;
        SummaryForegroundService.stop(1,100,"fenced");SummaryForegroundService.stop(0,101,"fenced");SummaryForegroundService.stop(0,100,"old-token");
        check(tasks().size()==1&&PowerManager.heldCount()==1,"wrong tuple cannot stop");
        Intent cancel=n.actions.get(1).intent;
        cancel.putExtra(SummaryForegroundService.EXTRA_OWNER,101L);service.onStartCommand(cancel,0,2);
        check(tasks().size()==1&&cancelled==0,"stale notification cannot cancel");
        cancel.putExtra(SummaryForegroundService.EXTRA_OWNER,100L);service.onStartCommand(cancel,0,3);
        empty();check(cancelled==1&&failures.isEmpty(),"notification cancellation callback once");
        service.onStartCommand(cancel,0,4);check(cancelled==1,"duplicate cancellation ignored");

        reset();check(start("logout"),"logout task");deliver();SummaryForegroundService.stopOwner(0,999);
        check(tasks().size()==1,"old owner cannot stop replacement");SummaryForegroundService.stopOwner(0,100);
        empty();check(cancelled==1,"logout cancels owner task");

        reset();check(start("owner-changed"),"owner task");deliver();UserConfig.configs[0].owner=200;Handler.advance(1_000);
        empty();check(cancelled==1,"periodic owner mismatch cancels");

        reset();check(start("worker-stop"),"worker-stop task");deliver();Looper.isMain=false;stop("worker-stop");
        check(tasks().size()==1,"background stop queued to main");Looper.isMain=true;Handler.drain();empty();
        check(failures.isEmpty()&&cancelled==0,"controller stop is quiet");

        reset();check(start("privacy"),"privacy task");deliver();
        for(int i=0;i<100;i++)SummaryForegroundService.update(0,100,"privacy","sk-secret 聊天私密正文 保存",i,100);
        check(NotificationManager.notifications==0,"streaming updates throttled");Handler.advance(1_000);
        check(NotificationManager.notifications==1,"one update each second");
        check(!NotificationManager.last.text.contains("secret")&&!NotificationManager.last.text.contains("私密"),"notification only fixed stage");
        check(NotificationManager.last.text.startsWith("正在保存总结"),"safe stage mapping");
        check(NotificationManager.last.progress==99&&NotificationManager.last.max==100,"progress retained");
        for(int i=0;i<100;i++)SummaryForegroundService.update(0,100,"privacy","secret",100,100);
        check(NotificationManager.notifications==1,"repeat same second suppressed");
        Handler.advance(1_000);check(NotificationManager.notifications==2,"latest progress eventually shown");stop("privacy");empty();

        reset();Context.permission=-1;check(start("no-post-permission"),"FGS allowed without notification permission");deliver();Handler.advance(1_000);
        check(tasks().size()==1&&PowerManager.heldCount()==1,"denied notification does not kill FGS");
        check(NotificationManager.notifications==0,"no unauthorized notify");stop("no-post-permission");empty();

        reset();check(start("notify-fail"),"notify task");deliver();NotificationManager.failNotify=true;Handler.advance(1_000);
        empty();check(failures.size()==1&&failures.get(0).contains("未完成"),"notification/runtime failure explicitly stops task");

        reset();check(start("destroyed"),"destroyed task");deliver();service.onDestroy();empty();
        check(failures.size()==1,"unexpected service destroy failure");check(Handler.size()==0,"destroy clears timers");

        reset();check(SummaryForegroundService.start(0,100,"legacy-callback",()->cancelled++),"four-arg starts");deliver();service.onTimeout(1,1);
        empty();check(cancelled==1,"failure falls back to cancellation");check(android.widget.Toast.text.contains("未完成"),"legacy callback safe explanation");

        reset();check(start("duplicate"),"first token");check(start("duplicate"),"same tuple idempotent");
        check(Context.starts.size()==1,"duplicate does not dispatch twice");check(!start("second-same-account"),"one task per account");
        check(!start(1,101,"duplicate"),"UUID collision cannot cross owner");deliver();stop("duplicate");empty();

        reset();check(start("multi-a"),"account a start");deliver();Handler.advance(1_000);
        check(start(1,101,"multi-b"),"account b start");deliver();
        check(tasks().size()==2&&PowerManager.heldCount()==1,"multiple accounts share single held lock");
        check(PowerManager.locks.get(PowerManager.locks.size()-1).until==1_000+LIMIT,"latest task extends only own deadline");
        Handler.advance(LIMIT-1_000);
        check(failures.size()==1&&tasks().size()==1,"older task times out independently");
        check(PowerManager.heldCount()==1,"other account stays protected");
        Handler.advance(1_000);empty();check(failures.size()==2,"each task deadline enforced");

        reset();check(start("pre-delivery-stop"),"pending start");stop("pre-delivery-stop");deliver();
        empty();check(failures.isEmpty()&&cancelled==0,"stale delivered start cannot resurrect");

        reset();check(start("delayed-host"),"delayed service");Handler.advance(9_000);deliver();
        check(PowerManager.locks.get(0).timeout==LIMIT-9_000,"startup delay subtracts from bounded wake");
        Handler.advance(LIMIT-9_000);empty();check(failures.size()==1,"startup cannot extend total deadline");

        reset();Build.VERSION.SDK_INT=25;check(start("pre-o"),"pre-O start");deliver();
        check(service.serviceType==0,"pre-Q foreground overload");stop("pre-o");empty();
        System.out.println("SummaryForegroundServiceTest: "+cases+" cases, "+assertions+" assertions passed (production service; simulated Android boundaries).");
    }
}
