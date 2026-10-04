package org.telegram.messenger;import org.telegram.tgnet.TLRPC;
public final class MessageObject {public TLRPC.Message messageOwner;public MessageObject(TLRPC.Message m){messageOwner=m;}public long getDialogId(){return messageOwner.dialogId;}}
