package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GiftServiceTest {
    private final LedgerStore store=mock(LedgerStore.class);
    private final PlatformTransactionManager tx=Fixtures.transactions();
    private final Fixtures.MutableClock clock=new Fixtures.MutableClock();
    private final MigrationStore migrations=mock(MigrationStore.class);
    private final GiftService service=new GiftService(store,tx,clock,Fixtures.mapper(),new GiftClassifier(Fixtures.mapper()),migrations);
    private LedgerStore.Receipt receipt(GiftData g){LedgerStore.Receipt r=new LedgerStore.Receipt();r.setId(7);r.setFingerprint(g.getFingerprint());r.setStatus("BOOKED");r.setDelta(30L);return r;}
    @Test void normalEventBooksBeforeCommit()throws Exception{GiftData g=Fixtures.gift();when(store.insertReceipt(g)).thenReturn(7L);assertFalse(service.receive(g).isDuplicate());org.mockito.InOrder ordered=inOrder(store,tx,migrations);ordered.verify(tx).getTransaction(any());ordered.verify(migrations).guard(g);ordered.verify(store).insertReceipt(g);ordered.verify(store).book(7,g,Fixtures.NOW);ordered.verify(tx).commit(any());}
    @Test void failureRollsBackWholeEvent()throws Exception{GiftData g=Fixtures.gift();when(store.insertReceipt(g)).thenReturn(7L);doThrow(new DataIntegrityViolationException("counter failed")).when(store).book(anyLong(),any(),anyLong());assertThrows(DataAccessException.class,()->service.receive(g));verify(tx).rollback(any());verify(tx,never()).commit(any());verify(store,never()).updateProfile(any());}
    @Test void validProfileIsReliablySavedWithItsNewReceipt()throws Exception{GiftData g=Fixtures.gift();when(store.insertReceipt(g)).thenReturn(7L);doThrow(new DataIntegrityViolationException("profile failed")).when(store).updateProfile(g);assertThrows(DataAccessException.class,()->service.receive(g));verify(tx).rollback(any());verify(tx,never()).commit(any());}
    @Test void duplicateOnlyReturnsOriginalEvenAfterWindow()throws Exception{GiftData g=Fixtures.gift();when(store.insertReceipt(g)).thenThrow(new DuplicateKeyException("duplicate uk_source"));when(store.receipt(g)).thenReturn(Optional.of(receipt(g)));clock.millis+=Periods.BACKFILL_MILLIS+100;assertTrue(service.receive(g).isDuplicate());verify(store,never()).book(anyLong(),any(),anyLong());verify(tx).rollback(any());}
    @Test void conflictingDuplicateCannotOverwriteReceipt()throws Exception{GiftData g=Fixtures.gift();LedgerStore.Receipt r=receipt(g);r.setFingerprint("different");when(store.insertReceipt(g)).thenThrow(new DuplicateKeyException("duplicate uk_source"));when(store.receipt(g)).thenReturn(Optional.of(r));assertEquals(409,assertThrows(RankException.class,()->service.receive(g)).getHttpStatus());verify(store,never()).book(anyLong(),any(),anyLong());}
    @Test void otherUniqueFailureIsNotClaimedAsDuplicate()throws Exception{GiftData g=Fixtures.gift();when(store.insertReceipt(g)).thenThrow(new DuplicateKeyException("duplicate PRIMARY"));assertThrows(DuplicateKeyException.class,()->service.receive(g));verify(store,never()).receipt(any());}
    @Test void filteredMessageDoesNotWriteCounters()throws Exception{GiftData g=Fixtures.gift();g.setStatus("FILTERED");when(store.insertReceipt(g)).thenReturn(7L);assertEquals("FILTERED",service.receive(g).getStatus());verify(store,never()).book(anyLong(),any(),anyLong());verify(tx).commit(any());}
    @Test void temporaryFailureRetriesTransactionRatherThanIncrement()throws Exception{GiftData g=Fixtures.gift();when(store.insertReceipt(g)).thenThrow(new CannotAcquireLockException("busy")).thenReturn(7L);service.receive(g);verify(store,times(2)).insertReceipt(g);verify(store).book(7,g,Fixtures.NOW);verify(tx).rollback(any());verify(tx).commit(any());}
    @Test void reviewedLateMessageChangesStateAndScoresInSameTransaction()throws Exception{
        GiftData g=Fixtures.gift();g.setFirstReceivedAt(Fixtures.NOW+Periods.BACKFILL_MILLIS+1);g=new GiftClassifier(Fixtures.mapper()).classify(Fixtures.app(),"room1",Fixtures.mapper().readTree(Fixtures.message("m1",Fixtures.NOW)),g.getFirstReceivedAt());
        LedgerStore.Receipt r=receipt(g);r.setStatus("PENDING_REVIEW");r.setPendingPayload(g.getPendingPayload());r.setFirstReceivedAt(g.getFirstReceivedAt());r.setAppId(g.getAppId());r.setRoomId(g.getRoomId());r.setMonth(g.getStorageMonth());when(store.lockReceipt(7)).thenReturn(Optional.of(r));when(store.completeReview(eq(7L),any(),eq("operator"),eq("已核对"),anyLong())).thenReturn(true);
        assertEquals("BOOKED",service.approvePending(7,"operator","已核对",null).getStatus());verify(store).book(eq(7L),any(),anyLong());verify(tx).commit(any());
    }
    @Test void preCutoverPendingMessageCannotBeApprovedIntoTheNewScore()throws Exception{
        GiftData g=Fixtures.gift();LedgerStore.Receipt r=receipt(g);r.setStatus("PENDING_REVIEW");r.setPendingPayload(g.getReviewPayload());r.setFirstReceivedAt(g.getFirstReceivedAt());r.setAppId(g.getAppId());r.setRoomId(g.getRoomId());r.setMonth(g.getStorageMonth());
        when(store.lockReceipt(7)).thenReturn(Optional.of(r));doAnswer(call->{((GiftData)call.getArgument(0)).setStatus("PENDING_REVIEW");return null;}).when(migrations).guard(any());
        assertThrows(RankException.class,()->service.approvePending(7,"operator","已核对",null));
        verify(store,never()).completeReview(anyLong(),any(),anyString(),anyString(),anyLong());
        verify(store,never()).book(anyLong(),any(),anyLong());
    }
    @Test void emptyMagicGiftIdCanBeReviewedWithoutDuplicateScoring()throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode message=(com.fasterxml.jackson.databind.node.ObjectNode)Fixtures.mapper().readTree(Fixtures.message("m1",Fixtures.NOW));
        message.put("sec_magic_gift_id","");message.put("gift_value",10);message.put("gift_num",1);message.remove("test");
        GiftData g=new GiftClassifier(Fixtures.mapper()).classify(Fixtures.app(),"room1",message,Fixtures.NOW);
        LedgerStore.Receipt r=receipt(g);r.setStatus("PENDING_REVIEW");r.setPendingPayload(g.getReviewPayload());r.setFirstReceivedAt(g.getFirstReceivedAt());r.setAppId(g.getAppId());r.setRoomId(g.getRoomId());r.setMonth(g.getStorageMonth());
        when(store.lockReceipt(7)).thenReturn(Optional.of(r));when(store.completeReview(eq(7L),any(),eq("operator"),eq("已核对"),anyLong())).thenReturn(true);
        GiftService.Result result=service.approvePending(7,"operator","已核对",null);
        assertEquals("BOOKED",result.getStatus());assertEquals(3L,result.getScoreDelta().longValue());
        r.setStatus("BOOKED");r.setDelta(3L);
        assertTrue(service.approvePending(7,"operator","已核对",null).isDuplicate());
        verify(store).completeReview(eq(7L),any(),eq("operator"),eq("已核对"),anyLong());verify(store).book(eq(7L),any(),anyLong());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"\"magic\"","123","false","{}","[]"})
    void specialOrMalformedMagicGiftCannotBeApproved(String value)throws Exception{
        com.fasterxml.jackson.databind.node.ObjectNode message=(com.fasterxml.jackson.databind.node.ObjectNode)Fixtures.mapper().readTree(Fixtures.message("m1",Fixtures.NOW));
        message.set("sec_magic_gift_id",Fixtures.mapper().readTree(value));
        GiftData g=new GiftClassifier(Fixtures.mapper()).classify(Fixtures.app(),"room1",message,Fixtures.NOW);
        LedgerStore.Receipt r=receipt(g);r.setStatus("PENDING_REVIEW");r.setPendingPayload(g.getPendingPayload());r.setFirstReceivedAt(g.getFirstReceivedAt());r.setAppId(g.getAppId());r.setRoomId(g.getRoomId());r.setMonth(g.getStorageMonth());
        when(store.lockReceipt(7)).thenReturn(Optional.of(r));
        assertThrows(RankException.class,()->service.approvePending(7,"operator","已核对",null));
        verify(store,never()).completeReview(anyLong(),any(),anyString(),anyString(),anyLong());verify(store,never()).book(anyLong(),any(),anyLong());
    }
}
