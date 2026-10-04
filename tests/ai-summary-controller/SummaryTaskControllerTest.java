package org.telegram.ui.Components;

import org.telegram.messenger.*;
import org.telegram.messenger.ai.*;
import org.telegram.tgnet.TLRPC;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

/** Executes the real controller with explicitly scheduled external callbacks. */
public final class SummaryTaskControllerTest {
    private static final long OWNER=1000, DIALOG=-100;
    private static int assertions, tests, failures;
    private static SummaryTaskController controller;

    public static void main(String[] args) throws Exception {
        run("detach preserves one ongoing task and reentry does not restart", SummaryTaskControllerTest::detach);
        run("cancel loading ignores late network callbacks", SummaryTaskControllerTest::cancelLoading);
        run("cancel generating ignores every late model callback", SummaryTaskControllerTest::cancelGenerating);
        run("old completion cannot replace or stop a newer task", SummaryTaskControllerTest::oldCallbacks);
        run("cancel before save worker prevents queued persistence", SummaryTaskControllerTest::cancelQueuedSave);
        run("owner change clears task and blocks persistence", SummaryTaskControllerTest::ownerChange);
        run("selected-account change releases detached task", SummaryTaskControllerTest::selectedAccount);
        run("pending source edit invalidates a stale load response", SummaryTaskControllerTest::editDuringLoad);
        run("source deletion invalidates generation", SummaryTaskControllerTest::deleteDuringGeneration);
        run("completed live source invalidates while history stays", SummaryTaskControllerTest::editAfterSuccess);
        run("source access revocation cancels network and FGS", SummaryTaskControllerTest::accessRevoked);
        run("error is terminal and old draft cannot revive", SummaryTaskControllerTest::lateErrorCallbacks);
        run("old checkpoint finish cannot remove the new marker", SummaryTaskControllerTest::checkpointIdentity);
        run("old expiry runnable cannot clear newer task", SummaryTaskControllerTest::expiryIdentity);
        run("complete generation archives once and advances once", SummaryTaskControllerTest::successfulSave);
        run("duplicate success while saving does not archive twice", SummaryTaskControllerTest::duplicateSuccessWhileSaving);
        run("late model error and draft cannot disrupt accepted completion", SummaryTaskControllerTest::lateModelCallbacksWhileSaving);
        run("duplicate history response does not start a second inference", SummaryTaskControllerTest::duplicateLoaded);
        run("archive failure leaves cursor unchanged and retry only saves history", SummaryTaskControllerTest::failedArchive);
        run("old pending archive retry cannot save after task replacement", SummaryTaskControllerTest::staleArchiveRetry);
        run("source invalidation blocks a pending archive retry", SummaryTaskControllerTest::invalidatedArchiveRetry);
        run("cancellation during storage cannot advance or revive the task", SummaryTaskControllerTest::cancelDuringSave);
        run("partial and replay do not advance cursor", SummaryTaskControllerTest::partialAndReplay);
        run("empty batch skips model and archive", SummaryTaskControllerTest::emptyBatch);
        run("FGS refusal blocks network startup", SummaryTaskControllerTest::foregroundRefused);
        run("FGS asynchronous failure stops task and ignores old callback", SummaryTaskControllerTest::foregroundFailure);
        run("publish reentrant cancellation does not start networking", SummaryTaskControllerTest::cancelFromListener);
        run("input snapshots are bounded", SummaryTaskControllerTest::boundedInputs);
        run("listeners are weak references with no direct View field", SummaryTaskControllerTest::weakListeners);
        System.out.println("SummaryTaskControllerTest: "+(tests-failures)+" / "+tests+" cases passed, "+assertions+" assertions");
        if(failures!=0) throw new AssertionError(failures+" controller lifecycle cases failed");
    }
    private static void run(String name, Checked action) throws Exception {
        reset(); tests++;
        try {action.run();System.out.println("PASS "+name);} catch(Throwable error){failures++;System.out.println("FAIL "+name+": "+error);}
    }
    interface Checked {void run() throws Exception;}
    private static void reset() throws Exception {
        controller=SummaryTaskController.get(0);controller.clear();flush();
        Field field=SummaryTaskController.class.getDeclaredField("listeners");field.setAccessible(true);((ArrayList<?>)field.get(controller)).clear();
        Utilities.globalQueue.clear();AndroidUtilities.ui.clear();AndroidUtilities.delayed.clear();
        UserConfig.selectedAccount=0;UserConfig.getInstance(0).owner=OWNER;
        MessagesController.getMainSettings(0).values.clear();MessagesController.getMainSettings(0).failNextCommits=0;
        TLRPC.Chat chat=new TLRPC.Chat();chat.id=-DIALOG;chat.channel=true;MessagesController.getInstance(0).chats.clear();MessagesController.getInstance(0).chats.put(-DIALOG,chat);
        SummaryHistoryLoader.created.clear();AiSummaryClient.created.clear();SummaryHistoryStore.saved.clear();SummaryHistoryStore.fail=false;SummaryHistoryStore.duringSave=null;
        SummaryPublishStore.confirmed.clear();SummaryPublishStore.fail=false;
        SummaryForegroundService.active.clear();SummaryForegroundService.stopped.clear();SummaryForegroundService.rejectStart=false;SummaryForegroundService.starts=0;SummaryForegroundService.updates=0;
    }
    private static GroupSummarySheet.RangeRequest request(GroupSummarySheet.RangeMode mode){return new GroupSummarySheet.RangeRequest(mode,20,0,0,10,true,null,null,SummaryFilter.Options.DEFAULT);}
    private static SummaryTaskController.Session start(){return start(request(GroupSummarySheet.RangeMode.SINCE));}
    private static SummaryTaskController.Session start(GroupSummarySheet.RangeRequest request){check(controller.start(DIALOG,0,request,new AiSummarySettings.Config(),PromptOptions.DEFAULT),"task rejected");return controller.current();}
    private static ArrayList<SummaryMessage> messages(){return new ArrayList<>(Arrays.asList(new SummaryMessage(DIALOG,10,100,"Alice","original text")));}
    private static SummaryHistoryLoader loader(){return SummaryHistoryLoader.created.get(SummaryHistoryLoader.created.size()-1);}
    private static AiSummaryClient client(){return AiSummaryClient.created.get(AiSummaryClient.created.size()-1);}
    private static void loaded(){loader().callback.onLoaded(new SummaryHistoryLoader.Result(messages(),0,10,true));flush();}
    private static void flush(){int n=0;while(!Utilities.globalQueue.pending.isEmpty()||!AndroidUtilities.ui.pending.isEmpty()){if(++n>100)throw new AssertionError("queues loop");Utilities.globalQueue.drain();AndroidUtilities.ui.drain();}}
    private static void terminal(SummaryTaskController.Session task,SummaryTaskController.State state){check(task.state==state,"wrong state: "+task.state);check(!SummaryForegroundService.active.containsKey(task.taskId),"FGS was not released");}
    private static int cursor(){return SummaryStateStore.load(0,OWNER,DIALOG,0).cursor;}
    private static void edit(String text){TLRPC.Message message=new TLRPC.Message();message.id=10;message.dialogId=DIALOG;message.message=text;message.edit_date=1;NotificationCenter.getInstance(0).post(NotificationCenter.replaceMessagesObjects,0,DIALOG,new ArrayList<>(Arrays.asList(new MessageObject(message))));}

    private static void detach(){
        int[] changes={0};SummaryTaskController.Listener listener=task->changes[0]++;controller.addListener(listener);
        SummaryTaskController.Session task=start();loaded();controller.removeListener(listener);int count=changes[0];
        client().callback.onPartial("streamed");check(changes[0]==count,"detached listener was called");check(controller.current()==task&&task.running(),"detach cancelled task");
        check(SummaryTaskController.get(0).current()==task,"reentry replaced task");check(!controller.start(DIALOG,0,request(GroupSummarySheet.RangeMode.RECENT),new AiSummarySettings.Config(),PromptOptions.DEFAULT),"duplicate task accepted");
        check(SummaryHistoryLoader.created.size()==1&&AiSummaryClient.created.size()==1,"reentry duplicated requests");check(SummaryForegroundService.active.containsKey(task.taskId),"detached task lacks FGS");
    }
    private static void cancelLoading(){SummaryTaskController.Session task=start();SummaryHistoryLoader old=loader();controller.cancel();old.callback.onLoaded(new SummaryHistoryLoader.Result(messages(),0,10,true));old.callback.onError("late");flush();terminal(task,SummaryTaskController.State.CANCELLED);check(old.cancelled&&AiSummaryClient.created.isEmpty(),"cancelled loader started model");check(SummaryTaskCheckpoint.read(0,OWNER)==null,"queued checkpoint begin survived cancellation/finish");}
    private static void cancelGenerating(){SummaryTaskController.Session task=start();loaded();AiSummaryClient old=client();controller.cancel();old.callback.onPartial("late");old.callback.onSuccess("late success");old.callback.onError("late error");flush();terminal(task,SummaryTaskController.State.CANCELLED);check(old.cancelled&&task.draft==null,"model revived cancelled draft");check(SummaryHistoryStore.saved.isEmpty()&&cursor()==0,"cancelled task persisted");}
    private static void oldCallbacks(){start();loaded();AiSummaryClient old=client();controller.cancel();SummaryTaskController.Session next=start();old.callback.onSuccess("old");old.callback.onError("old");flush();check(controller.current()==next&&next.state==SummaryTaskController.State.LOADING,"old callback replaced new task");check(SummaryForegroundService.active.containsKey(next.taskId),"old callback stopped new FGS");}
    private static void cancelQueuedSave(){SummaryTaskController.Session task=start();loaded();client().callback.onSuccess("complete text");controller.cancel();flush();terminal(task,SummaryTaskController.State.CANCELLED);check(SummaryHistoryStore.saved.isEmpty(),"cancelled queued worker still archived text");check(cursor()==0,"cancelled queued worker advanced cursor");}
    private static void ownerChange(){SummaryTaskController.Session task=start();loaded();AiSummaryClient old=client();UserConfig.getInstance(0).owner=2000;old.callback.onSuccess("old owner result");flush();check(controller.current()==null,"slot identity change retained task");check(old.cancelled&&!SummaryForegroundService.active.containsKey(task.taskId),"identity change did not release IO");check(SummaryHistoryStore.saved.isEmpty(),"old owner result was persisted");}
    private static void selectedAccount(){SummaryTaskController.Session task=start();UserConfig.selectedAccount=1;NotificationCenter.getGlobalInstance().post(NotificationCenter.activeAccountChanged,-1);flush();check(controller.current()==null&&!SummaryForegroundService.active.containsKey(task.taskId),"selected account change retained task");}
    private static void editDuringLoad(){SummaryTaskController.Session task=start();edit("edited");loaded();flush();terminal(task,SummaryTaskController.State.INVALIDATED);check(AiSummaryClient.created.isEmpty(),"stale load started inference");}
    private static void deleteDuringGeneration(){SummaryTaskController.Session task=start();loaded();AiSummaryClient old=client();NotificationCenter.getInstance(0).post(NotificationCenter.messagesDeleted,0,new ArrayList<>(Arrays.asList(10)),100L,false);old.callback.onSuccess("late");flush();terminal(task,SummaryTaskController.State.INVALIDATED);check(old.cancelled&&task.sources==null&&SummaryHistoryStore.saved.isEmpty(),"deleted source result survived");}
    private static void editAfterSuccess(){SummaryTaskController.Session task=start();loaded();client().callback.onSuccess("saved summary");flush();edit("edited after success");terminal(task,SummaryTaskController.State.INVALIDATED);check(task.sources==null&&task.summary==null,"old live snapshot retained");check(SummaryHistoryStore.saved.size()==1,"source edit incorrectly erased immutable history");}
    private static void accessRevoked(){SummaryTaskController.Session task=start();loaded();MessagesController.getInstance(0).getChat(100).kicked=true;NotificationCenter.getInstance(0).post(NotificationCenter.updateInterfaces,0);terminal(task,SummaryTaskController.State.INVALIDATED);check(client().cancelled,"revoked access left model active");}
    private static void lateErrorCallbacks(){SummaryTaskController.Session task=start();loaded();AiSummaryClient old=client();old.callback.onError("network failed");old.callback.onPartial("late draft");old.callback.onSuccess("late complete");flush();terminal(task,SummaryTaskController.State.ERROR);check(task.summary==null&&SummaryHistoryStore.saved.isEmpty(),"error resurrected success");}
    private static void checkpointIdentity(){SummaryTaskController.Session old=start();Utilities.globalQueue.runNext();controller.cancel();SummaryTaskController.Session next=start();Utilities.globalQueue.runLast();check(SummaryTaskCheckpoint.read(0,OWNER).taskId.equals(next.taskId),"new checkpoint missing");Utilities.globalQueue.drain();check(SummaryTaskCheckpoint.read(0,OWNER).taskId.equals(next.taskId),"old finish cleared new checkpoint");check(!old.taskId.equals(next.taskId),"task IDs reused");}
    private static void expiryIdentity(){start();controller.cancel();Runnable oldExpiry=AndroidUtilities.delayed.get(0);SummaryTaskController.Session next=start();oldExpiry.run();check(controller.current()==next,"old expiry cleared new task");}
    private static void successfulSave(){SummaryTaskController.Session task=start();loaded();AiSummaryClient old=client();old.callback.onSuccess("summary");flush();terminal(task,SummaryTaskController.State.SUCCESS);check(task.historySaved&&task.committed&&cursor()==10,"success flags/cursor disagree");check(SummaryHistoryStore.saved.size()==1&&SummaryHistoryStore.saved.get(0).sources.size()==1,"success archive missing");check(SummaryTaskCheckpoint.read(0,OWNER)==null,"successful checkpoint remains");old.callback.onSuccess("duplicate");flush();check(SummaryHistoryStore.saved.size()==1&&cursor()==10,"duplicate success republished");}
    private static void duplicateSuccessWhileSaving(){SummaryTaskController.Session task=start();loaded();AiSummaryClient same=client();same.callback.onSuccess("first accepted summary");same.callback.onSuccess("duplicate success before save worker");flush();terminal(task,SummaryTaskController.State.SUCCESS);check(SummaryHistoryStore.saved.size()==1,"duplicate completion archived twice while SAVING");check(task.summary.equals("first accepted summary"),"duplicate completion replaced accepted text");}
    private static void lateModelCallbacksWhileSaving(){SummaryTaskController.Session task=start();loaded();AiSummaryClient same=client();same.callback.onSuccess("accepted summary");same.callback.onPartial("late draft");same.callback.onError("late error after success");flush();terminal(task,SummaryTaskController.State.SUCCESS);check(task.draft==null&&task.summary.equals("accepted summary"),"late model event changed accepted completion");check(SummaryHistoryStore.saved.size()==1&&cursor()==10,"late model error suppressed accepted persistence");}
    private static void duplicateLoaded(){start();SummaryHistoryLoader same=loader();loaded();same.callback.onLoaded(new SummaryHistoryLoader.Result(messages(),0,10,true));flush();check(AiSummaryClient.created.size()==1,"duplicate loader response launched another model request");}
    private static SummaryTaskController.Session archiveFailure(){SummaryHistoryStore.fail=true;SummaryTaskController.Session task=start();loaded();client().callback.onSuccess("generated summary");flush();return task;}
    private static void failedArchive(){SummaryTaskController.Session task=archiveFailure();terminal(task,SummaryTaskController.State.SUCCESS);check(!task.historySaved&&!task.committed&&cursor()==0,"failed archive advanced coverage");check(task.historyNotice!=null,"archive failure was not disclosed");SummaryHistoryStore.fail=false;controller.retryHistorySave();flush();check(task.historySaved&&SummaryHistoryStore.saved.size()==1,"archive retry failed");check(!task.committed&&cursor()==0,"archive-only retry advanced cursor");}
    private static void staleArchiveRetry(){archiveFailure();SummaryHistoryStore.fail=false;controller.retryHistorySave();SummaryTaskController.Session next=start();flush();check(controller.current()==next&&next.running(),"old retry altered replacement task");check(SummaryHistoryStore.saved.isEmpty(),"old pending archive retry wrote after replacement");}
    private static void invalidatedArchiveRetry(){SummaryTaskController.Session task=archiveFailure();SummaryHistoryStore.fail=false;controller.retryHistorySave();edit("edited before retry worker");flush();terminal(task,SummaryTaskController.State.INVALIDATED);check(SummaryHistoryStore.saved.isEmpty(),"invalidated pending retry still archived");}
    private static void cancelDuringSave(){SummaryTaskController.Session task=start();loaded();SummaryHistoryStore.duringSave=()->controller.cancel();client().callback.onSuccess("completed before cancellation");flush();terminal(task,SummaryTaskController.State.CANCELLED);check(cursor()==0,"cancellation during archive advanced cursor");check(task.historySaved==false,"late storage result revived cancelled UI");}
    private static void partialAndReplay(){SummaryTaskController.Session task=start();loader().callback.onLoaded(new SummaryHistoryLoader.Result(messages(),0,10,false));flush();client().callback.onSuccess("partial coverage, complete model output");flush();terminal(task,SummaryTaskController.State.SUCCESS);check(task.historySaved&&!task.committed&&cursor()==0&&SummaryHistoryStore.saved.get(0).partial,"partial coverage advanced cursor or was not archived");SummaryHistoryLoader.Result history=new SummaryHistoryLoader.Result(messages(),0,10,true);int count=SummaryHistoryLoader.created.size();task=start(new GroupSummarySheet.RangeRequest(GroupSummarySheet.RangeMode.REPLAY,20,0,0,10,false,history,messages(),null));flush();check(SummaryHistoryLoader.created.size()==count,"replay refetched history");client().callback.onSuccess("replay");flush();check(cursor()==0&&!task.committed,"replay advanced cursor");}
    private static void emptyBatch(){SummaryTaskController.Session task=start();loader().callback.onLoaded(new SummaryHistoryLoader.Result(new ArrayList<>(),0,10,true));flush();terminal(task,SummaryTaskController.State.SUCCESS);check(AiSummaryClient.created.isEmpty()&&SummaryHistoryStore.saved.isEmpty(),"empty batch invoked model/archive");check(cursor()==10,"fully scanned empty batch did not commit coverage");}
    private static void foregroundRefused(){SummaryForegroundService.rejectStart=true;SummaryTaskController.Session task=start();check(!task.running(),"FGS refusal left task running");check(SummaryHistoryLoader.created.isEmpty()&&AiSummaryClient.created.isEmpty(),"network started without FGS");}
    private static void foregroundFailure(){SummaryTaskController.Session task=start();SummaryForegroundService.Task registration=SummaryForegroundService.active.get(task.taskId);check(registration!=null,"FGS registration missing");registration.failure.onFailure("background support failed");terminal(task,SummaryTaskController.State.ERROR);SummaryTaskController.Session next=start();registration.failure.onFailure("late failure");check(controller.current()==next&&next.running()&&SummaryForegroundService.active.containsKey(next.taskId),"old FGS failure hit new task");}
    private static void cancelFromListener(){SummaryTaskController.Listener listener=task->{if(task!=null&&task.state==SummaryTaskController.State.LOADING)controller.cancel();};controller.addListener(listener);SummaryTaskController.Session task=start();controller.removeListener(listener);flush();terminal(task,SummaryTaskController.State.CANCELLED);check(SummaryHistoryLoader.created.isEmpty()&&AiSummaryClient.created.isEmpty(),"reentrant cancellation still started IO");check(SummaryTaskCheckpoint.read(0,OWNER)==null,"reentrant cancellation left checkpoint marker");}
    private static void boundedInputs(){SummaryTaskController.Session task=start();loaded();for(int i=0;i<150;i++)client().callback.onRequestInput(new AiSummaryClient.RequestInput(1));check(task.inputs.size()==128,"input snapshot count unbounded");controller.cancel();check(task.inputs.isEmpty(),"cancel retained model inputs");}
    private static void weakListeners() throws Exception {SummaryTaskController.Listener listener=task->{};controller.addListener(listener);Field field=SummaryTaskController.class.getDeclaredField("listeners");field.setAccessible(true);ArrayList<?> values=(ArrayList<?>)field.get(controller);check(values.get(0) instanceof WeakReference,"listener retained strongly");for(Field f:SummaryTaskController.class.getDeclaredFields()){String type=f.getType().getName();check(!type.startsWith("android.view.")&&!type.equals("android.app.Activity")&&!type.equals("android.content.Context"),"controller directly retains a UI object");}controller.removeListener(listener);}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
