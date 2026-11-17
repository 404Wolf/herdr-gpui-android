// t1xh's round-3 probe fixture (ReviewerClips), kept as a test fixture: real ClipStore + PendingDictations.
package dev.herdr.android.dictation;
import static org.junit.Assert.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class ClipsFixture {
  final ClipStore store;
  final PendingDictations pending;
  public ClipsFixture(File root){
    store=new ClipStore(root);
    pending=new PendingDictations(store,(c,f)->"speech",new CourierDegradedTest.Scheduler(new AtomicLong()),Runnable::run);
  }
  public String record(){ClipStore.Recording r=store.record("A",1);assertTrue(r.write(new byte[32],32));return r.keep().id;}
  public boolean hold(List<String> ids){return pending.hold(ids);}
  public boolean release(List<String> ids){return pending.release(ids);}
  public List<String> loose(){List<String> ids=new ArrayList<>();for(PendingClip c:pending.waiting())ids.add(c.id);for(PendingClip c:pending.parked())ids.add(c.id);return ids;}
  public boolean held(String id){return store.isHeld(id);}
  public boolean audio(String id){return store.audio(id).isFile();}
}
