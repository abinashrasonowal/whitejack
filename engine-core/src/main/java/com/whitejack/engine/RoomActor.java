package com.whitejack.engine;

import com.whitejack.contract.ErrorCode;
import com.whitejack.contract.GameDefinition;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.PlayerView;
import com.whitejack.contract.RandomSource;
import com.whitejack.contract.RoomCode;
import com.whitejack.contract.Seat;
import com.whitejack.contract.Validation;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * One room, one thread, one writer. Everything that mutates room or game state happens on this
 * thread and nothing else touches either, so no lock guards them.
 *
 * <p>The inbox is <b>bounded and {@link #offer} reports failure</b>. This thread is the slow
 * side of the pipe — it runs {@code reduce} plus a projection per viewer for every command — so
 * an unbounded queue would turn one looping client into heap exhaustion. Overload has to become
 * a rejection, never an OOM.
 */
public final class RoomActor implements Runnable, AutoCloseable {

    private static final Logger LOG = System.getLogger(RoomActor.class.getName());
    private static final int INBOX_CAPACITY = 256;
    private static final int MAX_NICK = 24;

    private final Room room;
    private final GameDefinition<?, ?, ?> def;
    private final Broadcaster broadcaster;
    private final TurnClock turnClock;
    private final Clock clock;
    private final BlockingQueue<Command> inbox = new ArrayBlockingQueue<>(INBOX_CAPACITY);
    private final Thread worker;

    private GameSession<?, ?, ?> session;
    private volatile boolean open = true;
    private volatile long lastActivityAt;

    private RoomActor(Room room, GameDefinition<?, ?, ?> def, Broadcaster broadcaster,
            TurnClock turnClock, Clock clock) {
        this.room = room;
        this.def = def;
        this.broadcaster = broadcaster;
        this.turnClock = turnClock;
        this.clock = clock;
        this.lastActivityAt = clock.millis();
        this.worker = new Thread(this, "room-" + room.code.value());
        this.worker.setDaemon(true); // a live room must never hold the JVM open
    }

    /**
     * Constructs fully, then starts — so the thread cannot observe a half-built actor. On Java
     * 21 the only change here is {@code Thread.ofVirtual()}.
     */
    static RoomActor start(Room room, GameDefinition<?, ?, ?> def, Broadcaster broadcaster,
            TurnClock turnClock, Clock clock) {
        RoomActor actor = new RoomActor(room, def, broadcaster, turnClock, clock);
        actor.worker.start();
        return actor;
    }

    /** @return false when the room is closed or its queue is full — the gateway then closes the socket */
    public boolean offer(Command command) {
        return open && inbox.offer(command);
    }

    public RoomCode code() {
        return room.code;
    }

    /** Which game this room plays; fixed at creation. */
    public String gameId() {
        return room.gameId;
    }

    public boolean isOpen() {
        return open;
    }

    public long lastActivityAt() {
        return lastActivityAt;
    }

    @Override
    public void run() {
        while (open) {
            Command command;
            try {
                command = inbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            lastActivityAt = clock.millis();
            try {
                handle(command);
            } catch (RuntimeException e) {
                // A game module that throws must not silently brick the room: without this the
                // actor thread dies, the registry keeps handing out a room whose queue nobody
                // drains, and every socket hangs forever with no log line.
                LOG.log(Level.ERROR, "room " + room.code.value() + " failed on " + command, e);
                fault(e);
                open = false;
            }
        }
        turnClock.cancel(room.code);
    }

    /**
     * Java 17 has no exhaustive switch over a sealed type, so this is an {@code instanceof}
     * chain with a loud default. Adding a command without handling it fails at runtime here
     * rather than silently doing nothing.
     */
    private void handle(Command command) {
        if (command instanceof Command.Join join) onJoin(join);
        else if (command instanceof Command.Disconnect disconnect) onDisconnect(disconnect);
        else if (command instanceof Command.Leave leave) onLeave(leave);
        else if (command instanceof Command.Start start) onStart(start);
        else if (command instanceof Command.Submit submit) onSubmit(submit);
        else if (command instanceof Command.Timeout timeout) onTimeout(timeout);
        else if (command instanceof Command.Close close) onClose(close);
        else throw new IllegalStateException("unhandled command: " + command);
    }

    // ---- lobby ----

    private void onJoin(Command.Join join) {
        Occupant existing = room.occupant(join.player());
        if (existing != null) {
            // Same cookie, another tab — or the same tab after a refresh. Either way the seat
            // was never lost, so this is a reconnect and costs only a snapshot.
            room.put(existing.with(join.socket()));
            syncTo(join.player());
            broadcast();
            return;
        }
        if (room.phase == Phase.FINISHED) {
            reject(join.player(), ErrorCode.WRONG_PHASE, "this table has finished");
            return;
        }
        String nick = join.nick() == null ? "" : join.nick().strip();
        if (nick.isEmpty() || nick.length() > MAX_NICK) {
            reject(join.player(), ErrorCode.MALFORMED_INTENT, "nickname must be 1.." + MAX_NICK + " characters");
            return;
        }
        if (!HmacRandom.isValidClientSeed(join.clientSeed())) {
            reject(join.player(), ErrorCode.MALFORMED_INTENT, "clientSeed must be 8..128 lower-case hex characters");
            return;
        }
        if (room.size() >= def.maxPlayers()) {
            reject(join.player(), ErrorCode.SEAT_UNAVAILABLE, "table is full");
            return;
        }
        Seat seat = new Seat(room.size(), join.player(), nick);
        if (session != null) {
            // Mid-hand seating is the game's call, not the engine's: blackjack and rummy
            // disagree, and hardcoding either answer here would make one game's rules the
            // platform's rules.
            Validation verdict = session.canJoin(seat);
            if (!verdict.isOk()) {
                rejectWith(join.player(), verdict);
                return;
            }
        }
        room.put(new Occupant(seat, join.clientSeed(), Set.of(join.socket())));
        if (room.hostId == null) room.hostId = join.player();
        broadcast();
    }

    private void onDisconnect(Command.Disconnect disconnect) {
        Occupant occupant = room.occupant(disconnect.player());
        if (occupant == null) return;

        Occupant remaining = occupant.without(disconnect.socket());
        room.put(remaining);
        if (!remaining.connected()) {
            // The seat is held and the turn clock keeps running: dropping is not quitting, and
            // a player who refreshes mid-turn should not gain thinking time by doing it.
            reassignHostIfNeeded(disconnect.player());
        }
        broadcast();
    }

    private void onLeave(Command.Leave leave) {
        Occupant occupant = room.occupant(leave.player());
        if (occupant == null) return;

        if (room.phase == Phase.LOBBY) {
            room.remove(leave.player());
        } else {
            // ponytail: mid-game the seat is only vacated of sockets, not released -- releasing
            // it would renumber seats the running game already named. The grace window that
            // finally frees it is milestone 2.
            room.put(new Occupant(occupant.seat(), occupant.clientSeed(), Set.of()));
        }
        reassignHostIfNeeded(leave.player());
        broadcast();
    }

    private void reassignHostIfNeeded(PlayerId player) {
        if (!player.equals(room.hostId)) return;
        PlayerId heir = room.longestSeatedConnected();
        if (heir != null) room.hostId = heir; // the room does not close when the host drops
    }

    private void onStart(Command.Start start) {
        if (!start.requester().equals(room.hostId)) {
            reject(start.requester(), ErrorCode.NOT_HOST, "only the host can start the game");
            return;
        }
        if (room.phase != Phase.LOBBY) {
            reject(start.requester(), ErrorCode.WRONG_PHASE, "the game is already under way");
            return;
        }
        if (room.size() < def.minPlayers()) {
            reject(start.requester(), ErrorCode.NOT_ENOUGH_PLAYERS,
                    def.id() + " needs " + def.minPlayers() + " players");
            return;
        }

        room.renumberSeats();
        room.seed = SeedCommit.fresh();
        RandomSource random = HmacRandom.forHand(room.seed.reveal(), room.clientSeeds(), 0);
        session = GameSession.start(def, room.seats(), room.options,
                new RoomContext(clock, random, room.seats()));
        room.phase = Phase.PLAYING;

        broadcast();
        rearmClock();
    }

    // ---- play ----

    private void onSubmit(Command.Submit submit) {
        if (session == null || room.phase != Phase.PLAYING) {
            rejectIntent(submit, ErrorCode.WRONG_PHASE, room.phase == Phase.FINISHED ? "the game is over" : "no game is running");
            return;
        }
        if (room.occupant(submit.actor()) == null) {
            rejectIntent(submit, ErrorCode.NOT_SEATED, "spectators cannot play");
            return;
        }

        Outcome outcome = session.submit(submit.intent(), submit.actor());
        if (!outcome.accepted()) {
            // Total no-op: nobody else is told the attempt was even made.
            Validation.Reject reject = (Validation.Reject) outcome.result();
            rejectIntent(submit, reject.code(), reject.detail());
            return;
        }

        broadcaster.toPlayer(submit.actor(), new Broadcaster.Accepted(submit.clientMsgId(), outcome.seq()));
        finishIfOver();
        broadcast();   // tell them it is their turn...
        rearmClock();  // ...and only then start their clock, so latency is not deducted from it
    }

    private void onTimeout(Command.Timeout timeout) {
        if (session == null || room.phase != Phase.PLAYING) return;
        if (timeout.armedAtSeq() != session.seq()) {
            // Fenced. The player beat the clock by milliseconds; cancel() could not recall a
            // message already queued, and without this the next player's turn auto-plays
            // instantly.
            LOG.log(Level.DEBUG, () -> "room " + room.code.value() + " dropped a stale timeout");
            return;
        }

        Outcome outcome = session.submitTimeout(timeout.actor());
        if (!outcome.accepted()) {
            // Re-arming here would freeze the room alive: same state, same timeout, forever.
            // Failing loudly is the lesser evil, and a contract test should have caught it.
            throw new IllegalStateException(
                    def.id() + ".onTimeout produced an intent validate rejected: " + outcome.result());
        }
        finishIfOver();
        broadcast();
        rearmClock();
    }

    /**
     * A game that has ended ends play at the table: later intents are refused and nobody new
     * sits down. The actor stays open, so players can still reconnect to the final view until
     * the host closes the room or it is reaped.
     */
    private void finishIfOver() {
        if (session.isComplete()) room.phase = Phase.FINISHED;
    }

    private void rearmClock() {
        if (room.phase != Phase.PLAYING) {
            turnClock.cancel(room.code);
            return;
        }
        session.turn().ifPresentOrElse(
                turn -> turnClock.arm(room.code, turn, session.seq(), this::offer),
                () -> turnClock.cancel(room.code));
    }

    private void onClose(Command.Close close) {
        if (!close.requester().equals(room.hostId)) {
            reject(close.requester(), ErrorCode.NOT_HOST, "only the host can close the room");
            return;
        }
        room.phase = Phase.FINISHED;
        turnClock.cancel(room.code);
        broadcast();
        open = false;
    }

    // ---- outbound ----

    private void broadcast() {
        broadcaster.toRoom(room.code, views());
    }

    private Map<PlayerId, PlayerView> views() {
        Map<PlayerId, PlayerView> views = new LinkedHashMap<>();
        for (Occupant occupant : room.occupants()) {
            views.put(occupant.seat().id(), viewFor(occupant.seat().id()));
        }
        return views;
    }

    private PlayerView viewFor(PlayerId viewer) {
        return session == null ? room.lobbyView() : session.view(Optional.of(viewer));
    }

    private void syncTo(PlayerId player) {
        long seq = session == null ? -1 : session.seq();
        broadcaster.toPlayer(player, new Snapshot(seq, viewFor(player)));
    }

    private void reject(PlayerId player, ErrorCode code, String detail) {
        broadcaster.toPlayer(player, new Broadcaster.Rejected(null, code, detail));
    }

    private void rejectWith(PlayerId player, Validation verdict) {
        Validation.Reject reject = (Validation.Reject) verdict;
        reject(player, reject.code(), reject.detail());
    }

    private void rejectIntent(Command.Submit submit, ErrorCode code, String detail) {
        broadcaster.toPlayer(submit.actor(), new Broadcaster.Rejected(submit.clientMsgId(), code, detail));
    }

    private void fault(RuntimeException cause) {
        turnClock.cancel(room.code);
        Broadcaster.Fault notice = new Broadcaster.Fault(room.code, cause.toString());
        for (Occupant occupant : room.occupants()) {
            broadcaster.toPlayer(occupant.seat().id(), notice);
        }
    }

    @Override
    public void close() {
        open = false;
        turnClock.cancel(room.code);
        worker.interrupt();
    }
}
