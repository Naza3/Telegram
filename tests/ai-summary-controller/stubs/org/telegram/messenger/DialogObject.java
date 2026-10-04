package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public final class DialogObject {
 public static long getPeerDialogId(TLRPC.Peer peer){return peer==null?0:peer.id;}
}
