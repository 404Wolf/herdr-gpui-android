package dev.herdr.android;
import static org.junit.Assert.*;
import dev.herdr.android.dictation.ClipsFixture;
import java.io.File;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * t1xh's round-3 probes of #20, kept as tests: the clear and its undo against the real outbox,
 * drafts, clip store and courier, killed between production effects, restarted the composer's way.
 */
public final class ClearRecoveryTest {
  @Rule public final TemporaryFolder dir=new TemporaryFolder();
  static final class Crash extends Error{}
  private final class World implements ClearUndo.Effects {
    final File file,clipDir;
    final OutboxCourier courier;
    final ClipsFixture clips;
    final ComposerDrafts drafts=new ComposerDrafts();
    String savedDrafts="",savedRecord="",crashAfter="";
    boolean failPersist;
    int notices,retries;
    World(){this(new File(dir.getRoot(),UUID.randomUUID()+".json"),new File(dir.getRoot(),UUID.randomUUID()+"-clips"));}
    World(File f,File d){
      file=f;clipDir=d;
      courier=new OutboxCourier(file,Runnable::run,(h,p,t)->{throw new AssertionError();});
      clips=new ClipsFixture(clipDir);
      courier.outbox().ready("h","boot",new HashSet<>(Arrays.asList("A","B")));
      drafts.setHost("h");
      drafts.switchTo("A");
    }
    void crash(String at){if(crashAfter.equals(at))throw new Crash();}
    public boolean cancelDictation(){return false;}
    public String draftText(){return drafts.text();}
    public void setDraft(String t,String revision){drafts.edit(t,revision);}
    public void restoreDraft(String t,String revision){drafts.edit(ComposerDrafts.join(t,drafts.text()),revision);}
    public List<String> clearable(){return courier.outbox().clearable("h",drafts.pane(),now());}
    public String messageText(String id){return courier.outbox().text(id);}
    public List<String> looseClips(){
      Set<String> used=new HashSet<>();for(String t:liveTexts())used.addAll(DictationToken.ids(t));
      List<String> ids=clips.loose();ids.removeAll(used);return ids;
    }
    public boolean holdClips(List<String> ids){boolean ok=clips.hold(ids);if(ok)crash("hold");return ok;}
    public boolean releaseClips(List<String> ids){return clips.release(ids);}
    public boolean clearMessages(List<String> ids,long until){boolean ok=courier.clear(ids,until);if(ok)crash("messages");return ok;}
    public boolean unclearMessages(List<String> ids){return courier.unclear(ids);}
    public void purgeMessages(List<String> ids){courier.purgeCleared(ids);}
    public boolean persist(String record){
      if(failPersist)return false;
      savedDrafts=drafts.encode();savedRecord=record;
      crash(record.contains("\"undone\":true")?"undone":"persist");return true;
    }
    public boolean outboxLoaded(){return courier.outbox().loaded();}
    public List<Outbox.Cleared> clearedMessages(long t){return courier.outbox().clearedOn("h",t);}
    public List<String> liveTexts(){List<String> out=new ArrayList<>(drafts.all());out.addAll(courier.outbox().liveTexts());return out;}
    public void retryLater(){retries++;}
    public void failed(String why){notices++;}
    World restart(){
      World after=new World(file,clipDir);
      after.drafts.switchTo("");after.drafts.restore(savedDrafts);
      after.savedDrafts=savedDrafts;after.savedRecord=savedRecord;return after;
    }
    ClearUndo attach(){
      ClearUndo c=new ClearUndo(this);c.resume(savedRecord,now());
      String before=drafts.pane();drafts.switchTo("A");
      if(c.endsOnPaneChange(before,OWNER))c.commit();c.reconcile(now());return c;
    }
  }
  private static final String OWNER = ComposerDrafts.key("h", "A");
  static long now(){return System.currentTimeMillis();}

  @Test public void aDurablyAcceptedUndoSurvivesRestartAfterTheOldClearDeadline(){
    World w=new World();w.drafts.edit("draft");w.courier.add("h","A","queued");
    long t=now()-ClearUndo.QUEUE_WINDOW_MS-1000;
    ClearUndo c=new ClearUndo(w);c.longPress(OWNER,t);
    // Deterministic clock positioning: undo accepted before the deadline; restart occurs later.
    w.crashAfter="undone";
    try{c.undo(OWNER, t+100);fail("crash expected");}catch(Crash expected){}
    assertTrue(w.savedRecord.contains("\"undone\":true"));
    World after=w.restart();after.attach();
    assertEquals("draft",after.drafts.text());
    assertEquals("accepted undo must override the old clear deadline",1,after.courier.outbox().shown("h","A",now()).size());
  }

  @Test public void aLooseRecordingIsRecoverableIfKilledAfterItsHoldBeforeTheRecord(){
    World w=new World();String id=w.clips.record();w.drafts.edit("note");
    ClearUndo c=new ClearUndo(w);c.tap(OWNER,true,now()); // existing draft-level clear
    w.crashAfter="hold";
    try{c.tap(OWNER,false,now());fail("crash expected");}catch(Crash expected){}
    assertTrue(w.clips.held(id));
    World after=w.restart();ClearUndo resumed=after.attach();
    assertTrue(resumed.undo(OWNER, now()));
    assertTrue("audio remains on disk",after.clips.audio(id));
    assertTrue("undo must restore the loose recording to waiting",after.clips.loose().contains(id));
  }

  @Test public void anInterruptedClearAdoptsGoneMessagesFromTheHostToo(){
    World w=new World();w.drafts.edit("note");
    Outbox.Message gone=w.courier.add("h","B","gone-pane message");
    w.courier.ready("h",1,"boot",Collections.singleton("A"));
    assertEquals(Outbox.State.GONE,w.courier.outbox().shown("h","A",now()).get(0).state);
    ClearUndo c=new ClearUndo(w);c.tap(OWNER,true,now());w.crashAfter="messages";
    try{c.tap(OWNER,false,now());fail("crash expected");}catch(Crash expected){}
    World after=w.restart();ClearUndo resumed=after.attach();assertTrue(resumed.undo(OWNER, now()));
    assertEquals("the host's GONE row must be restored to the same list",1,after.courier.outbox().shown("h","A",now()).size());
    assertEquals(gone.id,after.courier.outbox().shown("h","A",now()).get(0).id);
  }

  @Test public void theSixEarlierCasesAreFixedInTheirOrdinaryPaths(){
    World w=new World();w.drafts.edit("draft");
    String a=w.clips.record(),b=w.clips.record();
    Outbox.Message m=w.courier.add("h","A","queued "+DictationToken.of(a));
    ClearUndo c=new ClearUndo(w);c.longPress(OWNER,now());
    assertTrue(w.clips.held(a));assertTrue(w.clips.held(b));
    World after=w.restart();ClearUndo resumed=after.attach();assertTrue(resumed.open(now()));
    after.failPersist=true;assertFalse(resumed.undo(OWNER, now()));assertTrue(resumed.open(now()));
    after.failPersist=false;assertTrue(resumed.undo(OWNER, now()));
    assertEquals("draft",after.drafts.text());assertEquals(m.id,after.courier.outbox().shown("h","A",now()).get(0).id);
    assertFalse(after.clips.held(a));assertFalse(after.clips.held(b));
    Outbox box=new Outbox(new File(dir.getRoot(),"dispatch.json"));
    box.ready("h","boot",Collections.singleton("A"));Outbox.Message picked=box.add("h","A","never send",now());
    box.clearAll("h","A",now()+8000,now());assertFalse(box.sending(picked.id));
  }

  @Test public void anInterruptedOrdinaryQueuedMessageIsAdoptedAndUndone(){
    World w=new World();String id=w.clips.record();w.drafts.edit("draft");
    Outbox.Message m=w.courier.add("h","A","queued "+DictationToken.of(id));
    ClearUndo c=new ClearUndo(w);c.tap(OWNER,true,now());w.crashAfter="messages";
    try{c.tap(OWNER,false,now());fail("crash expected");}catch(Crash expected){}
    World after=w.restart();ClearUndo resumed=after.attach();assertTrue(resumed.undo(OWNER, now()));
    assertEquals("draft",after.drafts.text());assertEquals(m.id,after.courier.outbox().shown("h","A",now()).get(0).id);
    assertFalse(after.clips.held(id));
  }

  @Test public void undoMustNotDiscardAPendingLooseClipWhoseRecoveryWriteStillFails(){
    World w=new World();String id=w.clips.record();w.drafts.edit("note");
    ClearUndo c=new ClearUndo(w);c.tap(OWNER,true,now());w.crashAfter="hold";
    try{c.tap(OWNER,false,now());fail("crash expected");}catch(Crash expected){}
    World after=w.restart();
    File blocked=new File(after.clipDir,"index-v1.json.tmp");assertTrue(blocked.mkdir());
    ClearUndo resumed=after.attach();
    assertTrue("draft undo remains offered while pending clip rollback cannot be saved",resumed.open(now()));
    assertTrue("record must still identify the clip",after.savedRecord.contains(id));
    resumed.undo(OWNER, now());
    assertTrue(blocked.delete());
    resumed.reconcile(now());
    assertTrue("audio is still present",after.clips.audio(id));
    assertTrue("undo must preserve the pending clip's retry/recovery until it is released",after.clips.loose().contains(id));
  }
}
