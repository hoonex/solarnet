using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.Session;
using SolarNet.State;
using SolarNet.Transport;
using SolarNet.Turns;

internal static class Program
{
    private static readonly string[] Players = { "old-host", "successor", "peer-3" };

    private static async Task<int> Main()
    {
        try
        {
            await SurvivingPeerPromotesFromTrustedCheckpoint().ConfigureAwait(false);
            CheckpointRejectsInconsistentTurnMetadata();
            Console.WriteLine("PASS SurvivingPeerPromotesFromTrustedCheckpoint");
            Console.WriteLine("PASS CheckpointRejectsInconsistentTurnMetadata");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("FAIL authority checkpoint: " + ex);
            return 1;
        }
    }

    private static async Task SurvivingPeerPromotesFromTrustedCheckpoint()
    {
        var oldHub = new LoopbackTransportHub();
        var oldHostState = new CounterGameState();
        var successorState = new CounterGameState();
        var peer3State = new CounterGameState();
        var oldHost = new SolarTurnSession("authority-epoch-1", "old-host", oldHub.CreateEndpoint("old-host"), new TurnCoordinator(Players), oldHostState);
        var successor = new SolarTurnSession("authority-epoch-1", "old-host", oldHub.CreateEndpoint("successor"), null, successorState);
        var peer3 = new SolarTurnSession("authority-epoch-1", "old-host", oldHub.CreateEndpoint("peer-3"), null, peer3State);
        var faults = new List<Exception>();
        oldHost.ProtocolFaulted += faults.Add;
        successor.ProtocolFaulted += faults.Add;
        peer3.ProtocolFaulted += faults.Add;

        await oldHost.StartAsync().ConfigureAwait(false);
        await successor.StartAsync().ConfigureAwait(false);
        await peer3.StartAsync().ConfigureAwait(false);

        SolarAuthorityCheckpoint checkpoint;
        try
        {
            await Add(oldHost, 1).ConfigureAwait(false);      // turn 1 -> successor
            await Add(successor, 2).ConfigureAwait(false);    // turn 2 -> peer-3
            await Add(peer3, 3).ConfigureAwait(false);        // turn 3 -> old-host
            await Add(oldHost, 4).ConfigureAwait(false);      // turn 4 -> successor, round 2

            Equal(10, successorState.Value, "successor state before authority loss");
            Equal(4L, successor.KnownNextTurnIndex, "successor known turn before authority loss");
            Equal("successor", successor.KnownCurrentPlayerId, "successor is active at authority loss");
            Equal(2, successor.KnownRound, "successor known round before authority loss");
            checkpoint = SolarAuthorityPromotion.Capture(successor, Players, successorState);
            Equal("authority-epoch-1", checkpoint.SourceSessionId, "checkpoint source epoch");
            Equal(successor.LastStateHash, checkpoint.StateHash, "checkpoint trusted hash");
        }
        finally
        {
            await peer3.StopAsync().ConfigureAwait(false);
            await successor.StopAsync().ConfigureAwait(false);
            await oldHost.StopAsync().ConfigureAwait(false);
        }

        var newHub = new LoopbackTransportHub();
        var promotedState = new CounterGameState();
        var promoted = SolarAuthorityPromotion.CreatePromotedHost(
            "authority-epoch-2",
            "successor",
            newHub.CreateEndpoint("successor"),
            checkpoint,
            promotedState,
            journalCapacity: 1);
        var freshPeer3State = new CounterGameState();
        var freshPeer3 = new SolarTurnSession("authority-epoch-2", "successor", newHub.CreateEndpoint("peer-3"), null, freshPeer3State);
        var promotedFaults = new List<Exception>();
        var peer3Snapshot = Signal<SolarStateSnapshot>();
        promoted.ProtocolFaulted += promotedFaults.Add;
        freshPeer3.ProtocolFaulted += promotedFaults.Add;
        freshPeer3.SnapshotApplied += snapshot => peer3Snapshot.TrySetResult(snapshot);

        SolarTurnSession restartedOldHost = null;
        try
        {
            await promoted.StartAsync().ConfigureAwait(false);
            await freshPeer3.StartAsync().ConfigureAwait(false);

            Equal(10, promotedState.Value, "promoted host restores checkpoint state");
            Equal(4L, promoted.KnownNextTurnIndex, "promoted host restores turn index");
            Equal("successor", promoted.KnownCurrentPlayerId, "promoted host restores active player");
            Equal(2, promoted.KnownRound, "promoted host restores round");
            Equal(checkpoint.StateHash, promoted.LastStateHash, "promoted host hash after start");

            await freshPeer3.RequestResyncAsync(0).ConfigureAwait(false);
            await WaitAsync(peer3Snapshot.Task, "peer-3 snapshot from promoted authority").ConfigureAwait(false);
            AssertConverged(promoted, freshPeer3, promotedState, freshPeer3State, "peer-3 after promoted-host snapshot");

            await Add(promoted, 5).ConfigureAwait(false);      // turn 5 -> peer-3
            await Add(freshPeer3, 1).ConfigureAwait(false);    // turn 6 -> old-host
            Equal(16, promotedState.Value, "new authority continues deterministic turns");
            Equal("old-host", promoted.KnownCurrentPlayerId, "old host becomes next ordinary player");

            var restartedOldHostState = new CounterGameState();
            var oldHostSnapshot = Signal<SolarStateSnapshot>();
            restartedOldHost = new SolarTurnSession("authority-epoch-2", "successor", newHub.CreateEndpoint("old-host"), null, restartedOldHostState);
            restartedOldHost.ProtocolFaulted += promotedFaults.Add;
            restartedOldHost.SnapshotApplied += snapshot => oldHostSnapshot.TrySetResult(snapshot);
            await restartedOldHost.StartAsync().ConfigureAwait(false);
            await restartedOldHost.RequestResyncAsync(0).ConfigureAwait(false);
            await WaitAsync(oldHostSnapshot.Task, "restarted old host snapshot from successor").ConfigureAwait(false);

            Equal(16, restartedOldHostState.Value, "restarted former host receives successor state");
            Equal(6L, restartedOldHost.KnownNextTurnIndex, "restarted former host receives current turn");
            Equal("old-host", restartedOldHost.KnownCurrentPlayerId, "restarted former host retains player identity");
            Equal(promoted.LastStateHash, restartedOldHost.LastStateHash, "restarted former host hash convergence");

            await Add(restartedOldHost, 2).ConfigureAwait(false); // turn 7 -> successor
            Equal(18, promotedState.Value, "former host action accepted by successor authority");
            Equal(18, freshPeer3State.Value, "peer-3 sees former host action");
            Equal(18, restartedOldHostState.Value, "former host sees own committed action");
            Equal(7L, promoted.KnownNextTurnIndex, "new authority advances after former host action");
            Equal("successor", promoted.KnownCurrentPlayerId, "turn order continues across authority epoch");
            Equal(0, promotedFaults.Count, "protocol faults after authority promotion");
        }
        finally
        {
            if (restartedOldHost != null) await restartedOldHost.StopAsync().ConfigureAwait(false);
            await freshPeer3.StopAsync().ConfigureAwait(false);
            await promoted.StopAsync().ConfigureAwait(false);
        }
    }

    private static void CheckpointRejectsInconsistentTurnMetadata()
    {
        var state = new CounterGameState();
        var bytes = state.CaptureSnapshot();
        var hash = SolarStateDigest.Compute(bytes);
        var threw = false;
        try
        {
            new SolarAuthorityCheckpoint("bad", Players, 4, "peer-3", 2, hash, bytes);
        }
        catch (ArgumentException)
        {
            threw = true;
        }
        True(threw, "inconsistent checkpoint metadata is rejected");
    }

    private static Task Add(SolarTurnSession session, byte delta)
    {
        return session.SubmitActionAsync("add", new[] { delta });
    }

    private static void AssertConverged(
        SolarTurnSession host,
        SolarTurnSession client,
        CounterGameState hostState,
        CounterGameState clientState,
        string label)
    {
        Equal(hostState.Value, clientState.Value, label + " value");
        Equal(host.KnownNextTurnIndex, client.KnownNextTurnIndex, label + " next turn");
        Equal(host.KnownCurrentPlayerId, client.KnownCurrentPlayerId, label + " current player");
        Equal(host.KnownRound, client.KnownRound, label + " round");
        Equal(host.LastStateHash, client.LastStateHash, label + " state hash");
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
