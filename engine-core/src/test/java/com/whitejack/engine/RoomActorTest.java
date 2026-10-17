package com.whitejack.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.whitejack.contract.ErrorCode;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.PlayerView;
import com.whitejack.contract.RoomCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoomActorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RoomCode CODE = new RoomCode("K7M2QX");
    private static final PlayerId ABI = new PlayerId("abi");
    private static final PlayerId SAM = new PlayerId("sam");
    private static final SocketId TAB1 = new SocketId("s1");
    private static final SocketId TAB2 = new SocketId("s2");
    private static final String SEED = "9f3a1b2c";

    private final RecordingBroadcaster broadcaster = new RecordingBroadcaster();
    private final TurnClock turnClock = new TurnClock();
    private final List<RoomActor> started = new ArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(RoomActor::close);
        turnClock.close();
    }

    private RoomActor actor(FakeGame game, Broadcaster out) {
        RoomActor actor = RoomActor.start(
                new Room(CODE, game.id(), null, Map.of()), game, out, turnClock, Clock.systemUTC());
        started.add(actor);
        return actor;
    }

    private RoomActor actor(FakeGame game) {
        return actor(game, broadcaster);
    }

    private static Command.Join join(PlayerId player, SocketId socket) {
        return new Command.Join(player, socket, player.value(), SEED);
    }

    private static JsonNode bump(int events) {
        return MAPPER.createObjectNode().put("type", "bump").put("events", events);
    }

    private static LobbyView lobby(Map<PlayerId, PlayerView> views, PlayerId viewer) {
        return assertInstanceOf(LobbyView.class, views.get(viewer));
    }

    /** Plays a room up to a running game with one seated player. */
    private RoomActor playing(FakeGame game) throws InterruptedException {
        RoomActor actor = actor(game);
        actor.offer(join(ABI, TAB1));
        broadcaster.nextBroadcast();
        actor.offer(new Command.Start(ABI));
        broadcaster.nextBroadcast();
        broadcaster.drain();
        return actor;
    }

    // ---- lobby and identity ----

    @Test
    void theFirstPlayerThroughTheDoorBecomesHost() throws Exception {
        RoomActor actor = actor(new FakeGame());
        actor.offer(join(ABI, TAB1));

        LobbyView view = lobby(broadcaster.nextBroadcast(), ABI);
        assertEquals(ABI, view.host());
        assertEquals(Phase.LOBBY, view.phase());
        assertEquals(1, view.members().size());
        assertEquals("abi", view.members().get(0).nick());
    }

    @Test
    void aSecondSocketForTheSamePlayerIsAReconnectNotASecondSeat() throws Exception {
        RoomActor actor = actor(new FakeGame());
        actor.offer(join(ABI, TAB1));
        broadcaster.nextBroadcast();
        broadcaster.drain();

        actor.offer(join(ABI, TAB2));

        assertInstanceOf(Snapshot.class, broadcaster.nextDirect().message());
        assertEquals(1, lobby(broadcaster.nextBroadcast(), ABI).members().size(), "one cookie, one seat");
    }

    @Test
    void closingOneTabOfTwoLeavesThePlayerConnected() throws Exception {
        RoomActor actor = actor(new FakeGame());
        actor.offer(join(ABI, TAB1));
        actor.offer(join(ABI, TAB2));
        actor.offer(new Command.Disconnect(ABI, TAB1));

        LobbyView view = null;
        for (int i = 0; i < 3; i++) view = lobby(broadcaster.nextBroadcast(), ABI);
        assertTrue(view.members().get(0).connected(), "closing one tab must not start the turn timer");
    }

    @Test
    void theLastSocketClosingHoldsTheSeat() throws Exception {
        RoomActor actor = actor(new FakeGame());
        actor.offer(join(ABI, TAB1));
        broadcaster.nextBroadcast();
        actor.offer(new Command.Disconnect(ABI, TAB1));

        LobbyView view = lobby(broadcaster.nextBroadcast(), ABI);
        assertEquals(1, view.members().size(), "dropping is not quitting");
        assertFalse(view.members().get(0).connected());
    }

    @Test
    void theHostRoleTransfersAndTheRoomStaysOpen() throws Exception {
        RoomActor actor = actor(new FakeGame());
        actor.offer(join(ABI, TAB1));
        actor.offer(join(SAM, TAB2));
        broadcaster.nextBroadcast();
        broadcaster.nextBroadcast();

        actor.offer(new Command.Disconnect(ABI, TAB1));

        LobbyView view = lobby(broadcaster.nextBroadcast(), SAM);
        assertEquals(SAM, view.host());
        assertTrue(actor.isOpen(), "the room must not close when the host drops");
    }

    @Test
    void junkAtTheJoinBoundaryIsRejectedBeforeItCanReachAHand() throws Exception {
        RoomActor actor = actor(new FakeGame());

        actor.offer(new Command.Join(ABI, TAB1, "   ", SEED));
        assertEquals(ErrorCode.MALFORMED_INTENT, rejected(broadcaster.nextDirect()).error());

        actor.offer(new Command.Join(ABI, TAB1, "abi", "NOT-HEX"));
        assertEquals(ErrorCode.MALFORMED_INTENT, rejected(broadcaster.nextDirect()).error());

        actor.offer(new Command.Join(ABI, TAB1, "x".repeat(25), SEED));
        assertEquals(ErrorCode.MALFORMED_INTENT, rejected(broadcaster.nextDirect()).error());

        broadcaster.assertSilent();
    }

    // ---- starting ----

    @Test
    void onlyTheHostCanStartAndOnlyWithEnoughPlayers() throws Exception {
        RoomActor actor = actor(new FakeGame(null, 2));
        actor.offer(join(ABI, TAB1));
        actor.offer(join(SAM, TAB2));
        broadcaster.nextBroadcast();
        broadcaster.nextBroadcast();

        actor.offer(new Command.Start(SAM));
        assertEquals(ErrorCode.NOT_HOST, rejected(broadcaster.nextDirect()).error());

        RoomActor lonely = actor(new FakeGame(null, 2));
        lonely.offer(join(ABI, TAB1));
        lonely.offer(new Command.Start(ABI));
        assertEquals(ErrorCode.NOT_ENOUGH_PLAYERS, rejected(broadcaster.nextDirect()).error());
    }

    @Test
    void submittingBeforeTheGameStartsIsRejected() throws Exception {
        RoomActor actor = actor(new FakeGame());
        actor.offer(join(ABI, TAB1));
        broadcaster.nextBroadcast();
        broadcaster.drain();

        actor.offer(new Command.Submit(ABI, bump(1), "c1"));
        assertEquals(ErrorCode.WRONG_PHASE, rejected(broadcaster.nextDirect()).error());
    }

    // ---- play ----

    @Test
    void anAcceptedIntentIsAcknowledgedThenBroadcast() throws Exception {
        RoomActor actor = playing(new FakeGame());
        actor.offer(new Command.Submit(ABI, bump(2), "c1"));

        RecordingBroadcaster.Direct ack = broadcaster.nextDirect();
        assertEquals(ABI, ack.to());
        assertEquals(new Broadcaster.Accepted("c1", 1), ack.message());
        assertEquals(new FakeGame.View(List.of("abi-e0", "abi-e1"), true),
                broadcaster.nextBroadcast().get(ABI));
    }

    @Test
    void anIllegalIntentIsToldToNobodyButTheCaller() throws Exception {
        RoomActor actor = playing(new FakeGame());
        actor.offer(new Command.Submit(ABI, bump(-1), "c9"));

        Broadcaster.Rejected rejection = rejected(broadcaster.nextDirect());
        assertEquals("c9", rejection.clientMsgId(), "the reply must name the intent that caused it");
        assertEquals(ErrorCode.ILLEGAL_MOVE, rejection.error());
        broadcaster.assertSilent();
    }

    /**
     * The weekly bug this prevents: the timer fires at T−1ms, the player's real intent arrives
     * and is processed, {@code cancel()} cannot recall a message already queued — and the room
     * instantly auto-plays the <em>next</em> player's turn.
     */
    @Test
    void aTimeoutArmedAtAnOlderSequenceIsDropped() throws Exception {
        RoomActor actor = playing(new FakeGame());
        actor.offer(new Command.Submit(ABI, bump(1), "c1"));
        broadcaster.nextDirect();
        broadcaster.nextBroadcast();
        broadcaster.drain();

        actor.offer(new Command.Timeout(-1, ABI)); // armed before that intent landed
        broadcaster.assertSilent();

        actor.offer(new Command.Timeout(0, ABI)); // armed at the current sequence
        assertEquals(new FakeGame.View(List.of("abi-e0", "abi-e0"), true),
                broadcaster.nextBroadcast().get(ABI));
    }

    @Test
    void aGameThatThrowsClosesTheRoomLoudlyInsteadOfWedgingIt() throws Exception {
        RoomActor actor = playing(new FakeGame("abi-e0"));
        actor.offer(new Command.Submit(ABI, bump(1), "c1"));

        assertInstanceOf(Broadcaster.Fault.class, broadcaster.nextDirect().message());
        for (int i = 0; i < 50 && actor.isOpen(); i++) Thread.sleep(10);
        assertFalse(actor.isOpen(), "the actor thread died without closing the room");
        assertFalse(actor.offer(new Command.Submit(ABI, bump(1), "c2")), "a dead room must refuse work");
    }

    @Test
    void aFinishedGameFinishesTheTableButKeepsTheRoomOpen() throws Exception {
        RoomActor actor = playing(new FakeGame(null, 1, 3));
        actor.offer(new Command.Submit(ABI, bump(3), "c1"));
        broadcaster.nextDirect();
        assertEquals(new FakeGame.View(List.of("abi-e0", "abi-e1", "abi-e2"), true),
                broadcaster.nextBroadcast().get(ABI), "everyone sees the final position");

        actor.offer(new Command.Submit(ABI, bump(1), "c2"));
        assertEquals(ErrorCode.WRONG_PHASE, rejected(broadcaster.nextDirect()).error());
        actor.offer(join(SAM, TAB2));
        assertEquals(ErrorCode.WRONG_PHASE, rejected(broadcaster.nextDirect()).error());
        assertTrue(actor.isOpen(), "players can still reconnect to the final view");

        actor.offer(join(ABI, TAB2));
        assertInstanceOf(Snapshot.class, broadcaster.nextDirect().message());
    }

    @Test
    void onlyTheHostCanCloseTheRoom() throws Exception {
        RoomActor actor = actor(new FakeGame());
        actor.offer(join(ABI, TAB1));
        actor.offer(join(SAM, TAB2));
        broadcaster.nextBroadcast();
        broadcaster.nextBroadcast();
        broadcaster.drain();

        actor.offer(new Command.Close(SAM));
        assertEquals(ErrorCode.NOT_HOST, rejected(broadcaster.nextDirect()).error());

        actor.offer(new Command.Close(ABI));
        assertEquals(Phase.FINISHED, lobby(broadcaster.nextBroadcast(), ABI).phase());
    }

    // ---- the two claims about the queue ----

    /**
     * Engine invariant 1. Many threads submit at once; the room applies them in some total order
     * and loses none. A second writer would drop or interleave events here.
     */
    @Test
    void concurrentSubmitsProduceATotalOrder() throws Exception {
        RoomActor actor = playing(new FakeGame());
        int submitters = 8;
        int each = 10;

        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < submitters; t++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < each; i++) {
                    while (!actor.offer(new Command.Submit(ABI, bump(1), "c"))) Thread.onSpinWait();
                }
            });
            thread.start();
            threads.add(thread);
        }
        go.countDown();
        for (Thread thread : threads) thread.join(5_000);

        int expected = submitters * each;
        FakeGame.View view = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            view = (FakeGame.View) broadcaster.nextBroadcast().get(ABI);
            if (view.applied().size() == expected) break;
        }
        assertEquals(expected, view.applied().size(), "events were lost or duplicated");
    }

    /** Overload must become a rejection, never heap exhaustion. */
    @Test
    void aFullInboxRefusesWorkInsteadOfGrowing() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        RecordingBroadcaster wedged = new RecordingBroadcaster(gate);
        RoomActor actor = actor(new FakeGame(), wedged);
        actor.offer(join(ABI, TAB1)); // this one wedges the actor inside the broadcaster

        boolean refused = false;
        for (int i = 0; i < 1_000 && !refused; i++) {
            refused = !actor.offer(new Command.Submit(ABI, bump(1), "c" + i));
        }
        gate.countDown();
        assertTrue(refused, "the inbox accepted 1000 commands from a wedged room");
    }

    @Test
    void reconnectingMidGameGetsASnapshotAndNoEventTail() throws Exception {
        RoomActor actor = playing(new FakeGame());
        actor.offer(new Command.Submit(ABI, bump(2), "c1"));
        broadcaster.nextDirect();
        broadcaster.nextBroadcast();
        broadcaster.drain();

        actor.offer(join(ABI, TAB2));

        Snapshot snapshot = assertInstanceOf(Snapshot.class, broadcaster.nextDirect().message());
        assertEquals(1, snapshot.seq(), "the snapshot names the sequence it already reflects");
        assertEquals(new FakeGame.View(List.of("abi-e0", "abi-e1"), true), snapshot.view());
        assertNotEquals(-1, snapshot.seq());
    }

    private static Broadcaster.Rejected rejected(RecordingBroadcaster.Direct direct) {
        return assertInstanceOf(Broadcaster.Rejected.class, direct.message());
    }
}
