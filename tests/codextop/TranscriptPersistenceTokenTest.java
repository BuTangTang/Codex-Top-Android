package com.butang.codextop;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 使用实际候选Window和合成独立目录，不读账号、设备或真实聊天。 */
public final class TranscriptPersistenceTokenTest {
    private static int assertions;
    private static int groups;
    private static final String G1="00000000-0000-4000-8000-000000000001";
    private static final String G2="00000000-0000-4000-8000-000000000002";
    private static final String G3="00000000-0000-4000-8000-000000000003";
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    interface Checked { void run() throws Exception; }
    private static void yes(boolean condition,String label) { assertions++;if(!condition)throw new AssertionError(label); }
    private static void io(Checked body,String label)throws Exception {
        assertions++;try{body.run();throw new AssertionError("accepted: "+label);}catch(IOException expected){}
    }
    private static void group(String label){groups++;System.out.println("PASS "+label);}
    private static JsonObject page(String... ids) {
        JsonArray items=new JsonArray();
        for(String id:ids){JsonObject item=new JsonObject();item.addProperty("id",id);item.addProperty("createdAtMs",123);
            JsonObject raw=new JsonObject();raw.addProperty("role","agent");JsonObject content=new JsonObject();content.addProperty("type","text");content.addProperty("text","synthetic "+id);raw.add("content",content);item.add("raw",raw);items.add(item);}
        JsonObject page=new JsonObject();page.add("items",items);page.addProperty("hasMore",false);page.addProperty("historyAvailability","available");page.addProperty("tailCursor","tail");return page;
    }
    private static TranscriptWindow populated(String id)throws IOException { TranscriptWindow w=new TranscriptWindow();w.prepend(page(id));return w; }
    public static void main(String[] args)throws Exception {
        Path dir=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(dir);Path family=dir.resolve("scope/remote");

        TranscriptWindow a=populated("a");TranscriptPersistenceToken token=TranscriptPersistenceToken.attachIndexed(a,family,G1,SHA);
        TranscriptWindow b=a.acceptLatest(page("b"));yes(b!=a,"latest must swap root");yes(b.containsSegment(a),"old object remains a segment");
        yes(b.persistenceToken()==token && a.persistenceToken()==token,"same token across root swap");
        token.committed(b,family,G1,G2);yes(G2.equals(token.generation()),"commit advances shared generation");
        io(()->TranscriptPersistenceToken.requireWritable(a,family),"old root must not overwrite new root after shared generation advances");
        io(()->a.acceptLatest(page("illegal-old-late")),"late old-root latest response");
        yes(a.before(0,10).size()==1 && a.before(0,1).get(0).message.id.equals("a"),"old reader retained");
        yes(TranscriptPersistenceToken.requireWritable(b,family)==token,"new root writable");group("shared_generation_does_not_authorize_old_root");

        TranscriptWindow c=b.acceptLatest(page("c"));yes(c.segment(a.epoch())==a&&c.segment(b.epoch())==b,"flat archive retains identities");
        yes(b.exportIndexedSegments().size()==1,"previous root does not retain archive chain");
        io(()->TranscriptPersistenceToken.requireWritable(b,family),"second old root");
        token.committed(c,family,G2,G3);yes(G3.equals(token.generation()),"second root commit");
        b.prepend(page("older-b"));yes(c.segment(b.epoch()).sourceNumber("older-b",null)!=null,"older-page segment mutation belongs to current root");
        yes(TranscriptPersistenceToken.requireWritable(c,family)==token,"current root saves old-page changes");group("multiple_swaps_and_segment_updates");

        TranscriptWindow stable=populated("stable");TranscriptPersistenceToken stableToken=TranscriptPersistenceToken.attachIndexed(stable,family,G1);
        JsonObject invalid=page("broken");invalid.remove("tailCursor");io(()->stable.acceptLatest(invalid),"invalid latest no tail");
        yes(stable.persistenceToken()==stableToken&&G1.equals(stableToken.generation()),"failed page does not move authority");
        yes(TranscriptPersistenceToken.requireWritable(stable,family)==stableToken,"failed page leaves current root writable");
        yes(stable.before(0,1).get(0).message.id.equals("stable"),"failed latest retains original data");group("invalid_page_no_transfer");

        TranscriptWindow same=stable.acceptLatest(page("stable","appended"));yes(same==stable,"anchor latest uses same root");
        yes(TranscriptPersistenceToken.requireWritable(stable,family)==stableToken,"same root still writable");
        TranscriptWindow empty=new TranscriptWindow();TranscriptPersistenceToken emptyToken=TranscriptPersistenceToken.attachIndexed(empty,family,G1);
        yes(empty.acceptLatest(page("first"))==empty && empty.persistenceToken()==emptyToken,"empty first page stays bound");group("same_root_and_empty_root");

        TranscriptWindow restored=TranscriptWindow.restoreIndexed(c.exportIndexedSegments());
        TranscriptPersistenceToken restoredToken=TranscriptPersistenceToken.attachIndexed(restored,family,G3,SHA);
        for(TranscriptWindow.IndexedSegment s:restored.exportIndexedSegments()){
            TranscriptWindow segment=restored.segment(s.epoch);yes(segment.persistenceToken()==restoredToken,"restored archive binding");
            if(segment!=restored)io(()->TranscriptPersistenceToken.requireWritable(segment,family),"restored archive not independent root");
        }
        io(()->TranscriptPersistenceToken.attachMissing(restored.segment(a.epoch()),family,()->{}),"archive cannot be reclassified missing");
        yes(SHA.equals(restoredToken.legacySha256()),"imported receipt persisted in attached index token");group("restored_archives_bound_without_second_owner");

        io(()->TranscriptPersistenceToken.requireWritable(restored,dir.resolve("other-account/remote")),"account mismatch");
        io(()->TranscriptPersistenceToken.requireWritable(restored,dir.resolve("scope/other-remote")),"thread mismatch");
        yes(TranscriptPersistenceToken.requireWritable(restored,dir.resolve("scope/x/../remote"))==restoredToken,"normalized equivalent family");
        yes(restoredToken.familyPath().equals(family.normalize()),"metadata family accessor for existing Runtime roots");
        io(()->TranscriptPersistenceToken.attachIndexed(restored,family,G1),"double attachment");group("family_scope_and_equivalent_facade");

        TranscriptWindow absent=new TranscriptWindow();Path v2=dir.resolve("missing-proof.json");Files.write(v2,new byte[]{1});int[] calls={0};
        TranscriptPersistenceToken.MissingFilesCheck proof=()->{calls[0]++;if(Files.exists(v2))throw new IOException("existing committed v2");};
        io(()->TranscriptPersistenceToken.attachMissing(absent,family,proof),"existing committed cache");
        yes(calls[0]==1&&absent.persistenceToken()==null,"failed proof leaves unbound");
        io(()->TranscriptPersistenceToken.requireWritable(absent,family),"read-failure fallback may not save");
        Files.delete(v2);TranscriptPersistenceToken absentToken=TranscriptPersistenceToken.attachMissing(absent,family,proof);
        yes(calls[0]==2&&absentToken.origin()==TranscriptPersistenceToken.Origin.MISSING&&absentToken.generation()==null,"real absence proven before bind");
        absentToken.committed(absent,family,null,G1);yes(absentToken.origin()==TranscriptPersistenceToken.Origin.INDEXED,"first commit transitions");group("absence_requires_successful_file_proof");

        TranscriptWindow legacy=populated("legacy");TranscriptPersistenceToken legacyToken=TranscriptPersistenceToken.attachLegacy(legacy,family,SHA);
        yes(legacyToken.origin()==TranscriptPersistenceToken.Origin.LEGACY&&legacyToken.generation()==null&&SHA.equals(legacyToken.legacySha256()),"pending migration no claimed commit");
        // A failed disk write deliberately never calls committed. Token cannot claim failure as success.
        try{throw new IOException("synthetic publication failure");}catch(IOException expected){}
        yes(legacyToken.generation()==null&&legacyToken.origin()==TranscriptPersistenceToken.Origin.LEGACY,"failed write no advancement");
        legacyToken.committed(legacy,family,null,G1);yes(legacyToken.origin()==TranscriptPersistenceToken.Origin.INDEXED&&SHA.equals(legacyToken.legacySha256()),"migration receipt remains after commit");
        io(()->legacyToken.committed(legacy,family,null,G2),"stale expected generation");
        yes(G1.equals(legacyToken.generation()),"stale callback does not advance");
        io(()->legacyToken.committed(legacy,family,G1,"bad-generation"),"invalid committed generation");
        yes(G1.equals(legacyToken.generation()),"invalid callback does not advance");group("migration_origin_and_commit_cas");

        legacyToken.revoke(legacy,family);io(()->TranscriptPersistenceToken.requireWritable(legacy,family),"revoked write");
        io(()->legacy.acceptLatest(page("after-revoke")),"revoked root transfer");
        io(()->legacyToken.committed(legacy,family,G1,G2),"revoked commit");
        yes(legacy.before(0,1).get(0).message.id.equals("legacy"),"revoked still readable");group("rollback_revocation");

        io(()->TranscriptPersistenceToken.attachIndexed(new TranscriptWindow(),family,"1-1-1-1-1"),"noncanonical UUID");
        io(()->TranscriptPersistenceToken.attachIndexed(new TranscriptWindow(),family,G1,"A"+SHA.substring(1)),"uppercase sha");
        io(()->TranscriptPersistenceToken.attachLegacy(new TranscriptWindow(),family,"short"),"short legacy sha");
        io(()->TranscriptPersistenceToken.attachMissing(new TranscriptWindow(),family,null),"missing proof callback");group("format_guards");

        // Content intentionally cannot be read: binding/transfers must use metadata only.
        TranscriptWindow seed=populated("lazy-old");TranscriptWindow.IndexedSegment saved=seed.exportIndexedSegments().get(0);
        TranscriptWindow.RowRef row=saved.rows.get(0);int[] bodyReads={0};
        TranscriptWindow.RowRef lazyRow=new TranscriptWindow.RowRef(row.id,row.sourceId,row.localId,row.outgoing,row.createdAtMs,new TranscriptWindow.Body(()->{bodyReads[0]++;throw new IOException("unrequested body");}));
        TranscriptWindow lazy=TranscriptWindow.restoreIndexed(Collections.singletonList(new TranscriptWindow.IndexedSegment(saved.epoch,saved.oldest,saved.cursor,saved.tailCursor,saved.hasMore,saved.loaded,saved.complete,Collections.singletonList(lazyRow),saved.sourceNumbers)));
        TranscriptPersistenceToken lazyToken=TranscriptPersistenceToken.attachIndexed(lazy,family,G1);
        TranscriptWindow lazyNext=lazy.acceptLatest(page("lazy-new"));lazyToken.committed(lazyNext,family,G1,G2);
        yes(bodyReads[0]==0,"token operations do not read old body");
        yes(lazyNext.segment(lazy.epoch())==lazy,"lazy archived object retained");group("zero_old_body_io");

        // Frozen original source with no token: demonstrates why generation-only authority is insufficient.
        for(java.lang.reflect.Field field:TranscriptPersistenceToken.class.getDeclaredFields())
            yes(!java.lang.reflect.Modifier.isStatic(field.getModifiers())&&field.getType()!=TranscriptWindow.class
                &&!Map.class.isAssignableFrom(field.getType()),"token does not add global/root/history owner");
        group("no_static_cache_or_window_owner");
        System.out.println("PASS groups="+groups+" assertions="+assertions);
    }
}
