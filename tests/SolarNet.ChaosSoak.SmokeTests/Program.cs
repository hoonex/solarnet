using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Protocol;
using SolarNet.Session;
using SolarNet.State;
using SolarNet.Transport;
using SolarNet.Turns;

internal static class Program
{
    private const int Seed = 20260908;
    private const int Rounds = 60;

    private enum FaultMode
    {
        Clean = 0,
        Drop = 1,
        Duplicate = 2,
        Reorder = 3,
        CorruptState = 4
    }

    private static async Task<int> Main()
    {
        try
        {
            await DeterministicChaosSoakConverges().ConfigureAwait(false);
            Console.WriteLine("PASS DeterministicChaosSoakConverges");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("FAIL DeterministicChaosSoakConverges: " + ex);
            return 1;
        }
    }

    private static async Task DeterministicChaosSoakConverges()
    {
        var random = new Random(Seed);
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var peer2Transport = hub.CreateEndpoint("peer-2");
        var peer3Transport = hub.CreateEndpoint("peer-3");
        var peer4Transport = new ChaosCommittedTransport(hub.CreateEndpoint("peer-4"));

        var hostState = new CounterGameState();
        var peer2State = new CounterGameState();
        var peer3State = new CounterGameState();
        var peer4State = new CounterGameState();
        var players = new[] { "host", "peer-2", "peer-3", "peer-4" };
        var coordinator = new TurnCoordinator(players);

        var host = new SolarTurnSession("chaos-soak", "host", hostTransport, coordinator, hostState, journalCapacity: 32);
        var peer2 = new SolarTurnSession("chaos-soak", "host", peer2Transport, null, peer2State);
        var peer3 = new SolarTurnSession("chaos-soak", "host", peer3Transport, null, peer3State);
        var peer4 = new SolarTurnSession("chaos-soak", "host", peer4Transport, null, peer4State);

        var faults = new List<Exception>();
        var resyncFailures = new List<SolarResyncFailure>();
        var gapMismatches = 0;
        var digestMismatches = 0;
        var snapshots = 0;
        var peer4Commits = 0;
        var injected = new int[5];

        host.ProtocolFaulted += faults.Add;
        peer2.ProtocolFaulted += faults.Add;
        peer3.ProtocolFaulted += faults.Add;
        peer4.ProtocolFaulted += faults.Add;
        peer4.ResyncFailed += resyncFailures.Add;
        peer4.ActionCommitted += _ => peer4Commits++;
        peer4.SnapshotApplied += _ => snapshots++;
        peer4.StateMismatchDetected += mismatch =>
        {
            if (mismatch.Reason == SolarStateMismatchReason.TurnGap) gapMismatches++;
            if (mismatch.Reason == SolarStateMismatchReason.DigestMismatch) digestMismatches++;
        };

        await host.StartAsync().ConfigureAwait(false);
        await peer2.StartAsync().ConfigureAwait(false);
        await peer3.StartAsync().ConfigureAwait(false);
        await peer4.StartAsync().ConfigureAwait(false);

        var expectedValue = 0;
        try
        {
            for (var round = 0; round < Rounds; round++)
            {
                var mode = SelectFaultMode(round, random);
                injected[(int)mode]++;

                if (mode == FaultMode.Drop) peer4Transport.DropNextCommitted = true;
                else if (mode == FaultMode.Duplicate) peer4Transport.DuplicateNextCommitted = true;
                else if (mode == FaultMode.Reorder) peer4Transport.DelayNextCommitted = true;
                else if (mode == FaultMode.CorruptState) peer4State.ForceValue(peer4State.Value + 100000 + round);

                expectedValue += await SubmitAddAsync(host, random).ConfigureAwait(false);

                if (mode == FaultMode.CorruptState)
                    await WaitConvergedAsync(host, peer4, "digest recovery round " + round).ConfigureAwait(false);

                expectedValue += await SubmitAddAsync(peer2, random).ConfigureAwait(false);

                if (mode == FaultMode.Drop || mode == FaultMode.Reorder)
                    await WaitConvergedAsync(host, peer4, "gap recovery round " + round).ConfigureAwait(false);

                if (mode == FaultMode.Reorder)
                {
                    True(peer4Transport.HasDelayedCommitted, "reorder fault retained one stale commit");
                    await peer4Transport.ReleaseDelayedCommittedAsync().ConfigureAwait(false);
                    await WaitConvergedAsync(host, peer4, "stale reordered commit ignored round " + round).ConfigureAwait(false);
                }

                expectedValue += await SubmitAddAsync(peer3, random).ConfigureAwait(false);
                await WaitConvergedAsync(host, peer4, "before peer-4 turn round " + round).ConfigureAwait(false);
                Equal("peer-4", host.KnownCurrentPlayerId, "peer-4 active before final turn in round " + round);

                expectedValue += await SubmitAddAsync(peer4, random).ConfigureAwait(false);
                await WaitConvergedAsync(host, peer4, "round convergence " + round).ConfigureAwait(false);

                AssertAllConverged(
                    expectedValue,
                    host,
                    peer2,
                    peer3,
                    peer4,
                    hostState,
                    peer2State,
                    peer3State,
                    peer4State,
                    "round " + round);
            }

            var totalTurns = Rounds * players.Length;
            Equal((long)totalTurns, coordinator.TurnIndex, "authoritative total turn count");
            Equal((long)totalTurns, host.KnownNextTurnIndex, "host total turn count");
            Equal(totalTurns, peer4Commits, "faulted peer observes each authoritative commit exactly once");
            Equal(0, faults.Count, "protocol faults during chaos soak");
            Equal(0, resyncFailures.Count, "resync failures during chaos soak");

            True(injected[(int)FaultMode.Drop] > 0, "drop faults injected");
            True(injected[(int)FaultMode.Duplicate] > 0, "duplicate faults injected");
            True(injected[(int)FaultMode.Reorder] > 0, "reorder faults injected");
            True(injected[(int)FaultMode.CorruptState] > 0, "state corruption faults injected");
            True(gapMismatches > 0, "turn-gap recovery exercised");
            True(digestMismatches > 0, "digest recovery exercised");
            True(snapshots > 0, "snapshot recovery exercised");

            Equal(injected[(int)FaultMode.Drop], peer4Transport.DroppedCommittedCount, "drop injection accounting");
            Equal(injected[(int)FaultMode.Duplicate], peer4Transport.DuplicatedCommittedCount, "duplicate injection accounting");
            Equal(injected[(int)FaultMode.Reorder], peer4Transport.DelayedCommittedCount, "reorder injection accounting");
            Equal(peer4Transport.DelayedCommittedCount, peer4Transport.ReleasedCommittedCount, "all delayed commits eventually released");

            Console.WriteLine(
                "Chaos soak seed=" + Seed +
                " turns=" + totalTurns +
                " drops=" + peer4Transport.DroppedCommittedCount +
                " duplicates=" + peer4Transport.DuplicatedCommittedCount +
                " reorders=" + peer4Transport.DelayedCommittedCount +
                " gaps=" + gapMismatches +
                " digests=" + digestMismatches +
                " snapshots=" + snapshots);
        }
        finally
        {
            await peer4.StopAsync().ConfigureAwait(false);
            await peer3.StopAsync().ConfigureAwait(false);
            await peer2.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static FaultMode SelectFaultMode(int round, Random random)
    {
        if (round == 0) return FaultMode.Drop;
        if (round == 1) return FaultMode.Duplicate;
        if (round == 2) return FaultMode.Reorder;
        if (round == 3) return FaultMode.CorruptState;
        return (FaultMode)random.Next(0, 5);
    }

    private static async Task<int> SubmitAddAsync(SolarTurnSession session, Random random)
    {
        var delta = random.Next(1, 6);
        await session.SubmitActionAsync("add", new[] { (byte)delta }).ConfigureAwait(false);
        return delta;
    }

    private static async Task WaitConvergedAsync(SolarTurnSession host, SolarTurnSession client, string label)
    {
        await WaitUntilAsync(
            () => client.KnownNextTurnIndex == host.KnownNextTurnIndex &&
                  string.Equals(client.KnownCurrentPlayerId, host.KnownCurrentPlayerId, StringComparison.Ordinal) &&
                  string.Equals(client.LastStateHash, host.LastStateHash, StringComparison.Ordinal),
            label).ConfigureAwait(false);
    }

    private static async Task WaitUntilAsync(Func<bool> condition, string label)
    {
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        while (!condition())
        {
            if (DateTime.UtcNow >= deadline) throw new TimeoutException("Timed out waiting for " + label + ".");
            await Task.Delay(2).ConfigureAwait(false);
        }
    }

    private static void AssertAllConverged(
        int expectedValue,
        SolarTurnSession host,
        SolarTurnSession peer2,
        SolarTurnSession peer3,
        SolarTurnSession peer4,
        CounterGameState hostState,
        CounterGameState peer2State,
        CounterGameState peer3State,
        CounterGameState peer4State,
        string label)
    {
        Equal(expectedValue, hostState.Value, label + " host value");
        Equal(expectedValue, peer2State.Value, label + " peer2 value");
        Equal(expectedValue, peer3State.Value, label + " peer3 value");
        Equal(expectedValue, peer4State.Value, label + " peer4 value");

        Equal(host.KnownNextTurnIndex, peer2.KnownNextTurnIndex, label + " peer2 turn index");
        Equal(host.KnownNextTurnIndex, peer3.KnownNextTurnIndex, label + " peer3 turn index");
        Equal(host.KnownNextTurnIndex, peer4.KnownNextTurnIndex, label + " peer4 turn index");
        Equal(host.KnownRound, peer2.KnownRound, label + " peer2 round");
        Equal(host.KnownRound, peer3.KnownRound, label + " peer3 round");
        Equal(host.KnownRound, peer4.KnownRound, label + " peer4 round");
        Equal(host.KnownCurrentPlayerId, peer2.KnownCurrentPlayerId, label + " peer2 active player");
        Equal(host.KnownCurrentPlayerId, peer3.KnownCurrentPlayerId, label + " peer3 active player");
        Equal(host.KnownCurrentPlayerId, peer4.KnownCurrentPlayerId, label + " peer4 active player");
        Equal(host.LastStateHash, peer2.LastStateHash, label + " peer2 hash");
        Equal(host.LastStateHash, peer3.LastStateHash, label + " peer3 hash");
        Equal(host.LastStateHash, peer4.LastStateHash, label + " peer4 hash");
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
            checked { Value += delta; }
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

        public void ForceValue(int value)
        {
            Value = value;
        }
    }

    private sealed class ChaosCommittedTransport : ISolarTransport
    {
        private readonly ISolarTransport _inner;
        private SolarFrame _delayedCommitted;

        public ChaosCommittedTransport(ISolarTransport inner)
        {
            _inner = inner ?? throw new ArgumentNullException(nameof(inner));
        }

        public bool DropNextCommitted { get; set; }
        public bool DuplicateNextCommitted { get; set; }
        public bool DelayNextCommitted { get; set; }
        public int DroppedCommittedCount { get; private set; }
        public int DuplicatedCommittedCount { get; private set; }
        public int DelayedCommittedCount { get; private set; }
        public int ReleasedCommittedCount { get; private set; }
        public bool HasDelayedCommitted { get { return _delayedCommitted != null; } }
        public string LocalPeerId { get { return _inner.LocalPeerId; } }
        public event Func<SolarFrame, Task> FrameReceived;

        public async Task StartAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            _inner.FrameReceived += OnInnerFrameAsync;
            try
            {
                await _inner.StartAsync(cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                _inner.FrameReceived -= OnInnerFrameAsync;
                throw;
            }
        }

        public async Task StopAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            _inner.FrameReceived -= OnInnerFrameAsync;
            _delayedCommitted = null;
            await _inner.StopAsync(cancellationToken).ConfigureAwait(false);
        }

        public Task SendAsync(string remotePeerId, byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            return _inner.SendAsync(remotePeerId, frame, cancellationToken);
        }

        public Task BroadcastAsync(byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            return _inner.BroadcastAsync(frame, cancellationToken);
        }

        public async Task ReleaseDelayedCommittedAsync()
        {
            var frame = _delayedCommitted;
            if (frame == null) throw new InvalidOperationException("No committed frame is currently delayed.");
            _delayedCommitted = null;
            ReleasedCommittedCount++;
            await ForwardAsync(frame).ConfigureAwait(false);
        }

        private async Task OnInnerFrameAsync(SolarFrame frame)
        {
            var duplicate = false;
            if (DropNextCommitted || DuplicateNextCommitted || DelayNextCommitted)
            {
                var packet = SolarPacketCodec.Decode(frame.Data);
                if (packet.Type == SolarPacketType.TurnCommitted)
                {
                    if (DropNextCommitted)
                    {
                        DropNextCommitted = false;
                        DroppedCommittedCount++;
                        return;
                    }
                    if (DelayNextCommitted)
                    {
                        DelayNextCommitted = false;
                        if (_delayedCommitted != null) throw new InvalidOperationException("Only one delayed committed frame is supported at a time.");
                        _delayedCommitted = CloneFrame(frame);
                        DelayedCommittedCount++;
                        return;
                    }
                    if (DuplicateNextCommitted)
                    {
                        DuplicateNextCommitted = false;
                        DuplicatedCommittedCount++;
                        duplicate = true;
                    }
                }
            }

            await ForwardAsync(frame).ConfigureAwait(false);
            if (duplicate) await ForwardAsync(CloneFrame(frame)).ConfigureAwait(false);
        }

        private async Task ForwardAsync(SolarFrame frame)
        {
            var handlers = FrameReceived;
            if (handlers == null) return;
            foreach (var handler in handlers.GetInvocationList())
                await ((Func<SolarFrame, Task>)handler)(frame).ConfigureAwait(false);
        }

        private static SolarFrame CloneFrame(SolarFrame frame)
        {
            var data = new byte[frame.Data.Length];
            Buffer.BlockCopy(frame.Data, 0, data, 0, data.Length);
            return new SolarFrame(frame.RemotePeerId, data);
        }
    }
}
