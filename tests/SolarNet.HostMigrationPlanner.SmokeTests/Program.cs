using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.Room;
using SolarNet.Session;
using SolarNet.State;
using SolarNet.Transport;

internal static class Program
{
    private static readonly string[] Players = { "old-host", "successor", "peer-3" };

    private static async Task<int> Main()
    {
        try
        {
            await IndependentPeersDeriveSameSafePlan().ConfigureAwait(false);
            PlannerRejectsRosterMismatch();
            PlannerRejectsNonPlayingRoom();
            Console.WriteLine("PASS IndependentPeersDeriveSameSafePlan");
            Console.WriteLine("PASS PlannerRejectsRosterMismatch");
            Console.WriteLine("PASS PlannerRejectsNonPlayingRoom");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("FAIL host migration planner: " + ex);
            return 1;
        }
    }

    private static async Task IndependentPeersDeriveSameSafePlan()
    {
        var checkpoint = CreateCheckpoint(Players, 4, "successor", 2, 10);
        var snapshotA = CreatePlayingRoom(7, true, true);
        var snapshotB = CreatePlayingRoom(8, false, true); // transient link observation differs

        var planA = SolarHostMigrationPlanner.Create(snapshotA, checkpoint);
        var planB = SolarHostMigrationPlanner.Create(snapshotB, checkpoint);

        Equal("successor", planA.SuccessorPeerId, "lowest non-host room slot is successor");
        Equal(planA.SuccessorPeerId, planB.SuccessorPeerId, "connectivity disagreement cannot change successor");
        Equal(planA.NextGameSessionId, planB.NextGameSessionId, "independent peers derive same authority epoch ID");
        True(planA.NextGameSessionId.StartsWith("solarnet-migration-", StringComparison.Ordinal), "migration epoch prefix");
        True(!string.Equals(planA.NextGameSessionId, checkpoint.SourceSessionId, StringComparison.Ordinal), "migration rotates session ID");
        True(planA.IsSuccessor("successor"), "plan identifies elected peer");
        True(!planA.IsSuccessor("peer-3"), "non-successor cannot self-promote under plan");

        var hub = new LoopbackTransportHub();
        var promotedState = new CounterGameState();
        var promoted = SolarAuthorityPromotion.CreatePromotedHost(
            planA.NextGameSessionId,
            planA.SuccessorPeerId,
            hub.CreateEndpoint(planA.SuccessorPeerId),
            checkpoint,
            promotedState,
            journalCapacity: 1);
        var peer3State = new CounterGameState();
        var peer3 = new SolarTurnSession(planA.NextGameSessionId, planA.SuccessorPeerId, hub.CreateEndpoint("peer-3"), null, peer3State);
        var snapshotSignal = Signal<SolarStateSnapshot>();
        var faults = new List<Exception>();
        promoted.ProtocolFaulted += faults.Add;
        peer3.ProtocolFaulted += faults.Add;
        peer3.SnapshotApplied += snapshot => snapshotSignal.TrySetResult(snapshot);

        try
        {
            await promoted.StartAsync().ConfigureAwait(false);
            await peer3.StartAsync().ConfigureAwait(false);
            await peer3.RequestResyncAsync(0).ConfigureAwait(false);
            await WaitAsync(snapshotSignal.Task, "peer-3 restore from planned successor").ConfigureAwait(false);
            Equal(10, peer3State.Value, "planned successor serves checkpoint snapshot");
            Equal(4L, peer3.KnownNextTurnIndex, "planned successor serves checkpoint turn");

            await promoted.SubmitActionAsync("add", new byte[] { 1 }).ConfigureAwait(false);
            Equal(11, promotedState.Value, "planned successor commits next authoritative turn");
            Equal(11, peer3State.Value, "peer receives planned successor commit");
            Equal(5L, promoted.KnownNextTurnIndex, "turn advances under planned successor");
            Equal("peer-3", promoted.KnownCurrentPlayerId, "canonical turn order preserved");
            Equal(0, faults.Count, "protocol faults using migration plan");
        }
        finally
        {
            await peer3.StopAsync().ConfigureAwait(false);
            await promoted.StopAsync().ConfigureAwait(false);
        }
    }

    private static void PlannerRejectsRosterMismatch()
    {
        var checkpointPlayers = new[] { "old-host", "peer-3", "successor" };
        var checkpoint = CreateCheckpoint(checkpointPlayers, 4, "peer-3", 2, 10);
        var threw = false;
        try
        {
            SolarHostMigrationPlanner.Create(CreatePlayingRoom(7, true, true), checkpoint);
        }
        catch (InvalidOperationException)
        {
            threw = true;
        }
        True(threw, "room/checkpoint slot mismatch rejected");
    }

    private static void PlannerRejectsNonPlayingRoom()
    {
        var checkpoint = CreateCheckpoint(Players, 4, "successor", 2, 10);
        var players = CreatePlayers(true, true);
        var lobby = new SolarRoomSnapshot(4, "migration-room", "Migration Room", "old-host", "game-v1", 3, SolarRoomPhase.Lobby, string.Empty, players);
        var threw = false;
        try
        {
            SolarHostMigrationPlanner.Create(lobby, checkpoint);
        }
        catch (InvalidOperationException)
        {
            threw = true;
        }
        True(threw, "non-playing room cannot plan active-game migration");
    }

    private static SolarRoomSnapshot CreatePlayingRoom(long revision, bool successorConnected, bool peer3Connected)
    {
        return new SolarRoomSnapshot(
            revision,
            "migration-room",
            "Migration Room",
            "old-host",
            "game-v1",
            3,
            SolarRoomPhase.Playing,
            "game-epoch-1",
            CreatePlayers(successorConnected, peer3Connected));
    }

    private static SolarRoomPlayer[] CreatePlayers(bool successorConnected, bool peer3Connected)
    {
        return new[]
        {
            new SolarRoomPlayer(0, "old-host", "OLD HOST", true, true),
            new SolarRoomPlayer(1, "successor", "SUCCESSOR", true, successorConnected),
            new SolarRoomPlayer(2, "peer-3", "PEER 3", true, peer3Connected)
        };
    }

    private static SolarAuthorityCheckpoint CreateCheckpoint(string[] playerIds, long turn, string current, int round, int value)
    {
        var state = new CounterGameState(value);
        var bytes = state.CaptureSnapshot();
        return new SolarAuthorityCheckpoint("game-epoch-1", playerIds, turn, current, round, SolarStateDigest.Compute(bytes), bytes);
    }

    private static TaskCompletionSource<T> Signal<T>()
    {
        return new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private static async Task<T> WaitAsync<T>(Task<T> task, string label)
    {
        var timeout = Task.Delay(TimeSpan.FromSeconds(5));
        var completed = await Task.WhenAny(task, timeout).ConfigureAwait(false);
        if (!ReferenceEquals(completed, task)) throw new TimeoutException("Timed out waiting for " + label + ".");
        return await task.ConfigureAwait(false);
    }

    private static void True(bool value, string label)
    {
        if (!value) throw new Exception("Expected true: " + label);
    }

    private static void Equal<T>(T expected, T actual, string label)
    {
        if (!EqualityComparer<T>.Default.Equals(expected, actual))
            throw new Exception(label + " expected <" + expected + "> but got <" + actual + ">.");
    }

    private sealed class CounterGameState : ISolarGameStateMachine
    {
        public CounterGameState(int value = 0) { Value = value; }
        public int Value { get; private set; }

        public bool TryApply(SolarGameAction action)
        {
            if (!string.Equals(action.ActionKind, "add", StringComparison.Ordinal)) return false;
            if (action.Payload == null || action.Payload.Length != 1) return false;
            var delta = action.Payload[0];
            if (delta < 1 || delta > 5) return false;
            Value += delta;
            return true;
        }

        public byte[] CaptureSnapshot()
        {
            return new[]
            {
                (byte)((Value >> 24) & 0xff),
                (byte)((Value >> 16) & 0xff),
                (byte)((Value >> 8) & 0xff),
                (byte)(Value & 0xff)
            };
        }

        public void RestoreSnapshot(byte[] snapshot)
        {
            if (snapshot == null || snapshot.Length != 4) throw new ArgumentException("Counter snapshot must be exactly four bytes.", nameof(snapshot));
            Value = (snapshot[0] << 24) | (snapshot[1] << 16) | (snapshot[2] << 8) | snapshot[3];
        }
    }
}
