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
    private static async Task<int> Main()
    {
        var tests = new List<Func<Task>>
        {
            GameRuleRejectionDoesNotAdvanceTurn,
            DigestMismatchAutomaticallyRestoresSnapshot,
            MissedCommitReplaysFromJournalBeforeSnapshot
        };

        var passed = 0;
        foreach (var test in tests)
        {
            try
            {
                await test().ConfigureAwait(false);
                passed++;
                Console.WriteLine("PASS " + test.Method.Name);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FAIL " + test.Method.Name + ": " + ex);
                return 1;
            }
        }

        Console.WriteLine("SolarNet state-integrity smoke tests passed: " + passed + "/" + tests.Count);
        return 0;
    }

    private static async Task GameRuleRejectionDoesNotAdvanceTurn()
    {
        var hub = new LoopbackTransportHub();
        var hostState = new CounterGameState();
        var clientState = new CounterGameState();
        var coordinator = new TurnCoordinator(new[] { "host", "client" });
        var host = new SolarTurnSession("state-room", "host", hub.CreateEndpoint("host"), coordinator, hostState);
        var client = new SolarTurnSession("state-room", "host", hub.CreateEndpoint("client"), null, clientState);
        var clientRejections = new List<SolarTurnRejection>();
        client.ActionRejected += clientRejections.Add;

        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            await host.SubmitActionAsync("add", new byte[] { 2 }).ConfigureAwait(false);
            Equal(2, hostState.Value, "host state after accepted action");
            Equal(2, clientState.Value, "client state after accepted action");
            Equal(1L, coordinator.TurnIndex, "turn after accepted action");
            Equal(host.LastStateHash, client.LastStateHash, "state hash after accepted action");

            await client.SubmitActionAsync("add", new byte[] { 9 }).ConfigureAwait(false);
            Equal(1, clientRejections.Count, "client rejection count");
            Equal(SolarTurnRejectReason.GameRuleRejected, clientRejections[0].Reason, "game-rule rejection reason");
            Equal(1L, coordinator.TurnIndex, "rejected action must not advance turn");
            Equal(2, hostState.Value, "rejected action must not mutate host state");
            Equal(2, clientState.Value, "rejected action must not mutate client state");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task DigestMismatchAutomaticallyRestoresSnapshot()
    {
        var hub = new LoopbackTransportHub();
        var hostState = new CounterGameState();
        var clientState = new CounterGameState();
        var coordinator = new TurnCoordinator(new[] { "host", "client" });
        var host = new SolarTurnSession("digest-room", "host", hub.CreateEndpoint("host"), coordinator, hostState);
        var client = new SolarTurnSession("digest-room", "host", hub.CreateEndpoint("client"), null, clientState);
        var snapshotApplied = NewSignal<SolarStateSnapshot>();
        var mismatches = new List<SolarStateMismatch>();
        var faults = new List<Exception>();
        client.SnapshotApplied += snapshotApplied.SetResult;
        client.StateMismatchDetected += mismatches.Add;
        host.ProtocolFaulted += faults.Add;
        client.ProtocolFaulted += faults.Add;

        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            await host.SubmitActionAsync("add", new byte[] { 2 }).ConfigureAwait(false);
            clientState.ForceValue(50);

            await client.SubmitActionAsync("add", new byte[] { 1 }).ConfigureAwait(false);
            await WaitAsync(snapshotApplied.Task, "snapshot recovery").ConfigureAwait(false);

            Equal(3, hostState.Value, "authoritative state after second action");
            Equal(3, clientState.Value, "client state after automatic snapshot recovery");
            Equal(2L, client.KnownNextTurnIndex, "client next turn after snapshot recovery");
            Equal(host.LastStateHash, client.LastStateHash, "hash after snapshot recovery");
            Equal(1, mismatches.Count, "digest mismatch count");
            Equal(SolarStateMismatchReason.DigestMismatch, mismatches[0].Reason, "digest mismatch reason");
            Equal(0, faults.Count, "protocol faults during snapshot recovery");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task MissedCommitReplaysFromJournalBeforeSnapshot()
    {
        var hub = new LoopbackTransportHub();
        var hostState = new CounterGameState();
        var peer2State = new CounterGameState();
        var peer3State = new CounterGameState();
        var hostTransport = hub.CreateEndpoint("host");
        var peer2Transport = hub.CreateEndpoint("peer-2");
        var peer3Inner = hub.CreateEndpoint("peer-3");
        var peer3Transport = new DropCommittedTransport(peer3Inner);
        var coordinator = new TurnCoordinator(new[] { "host", "peer-2", "peer-3" });
        var host = new SolarTurnSession("journal-room", "host", hostTransport, coordinator, hostState, journalCapacity: 8);
        var peer2 = new SolarTurnSession("journal-room", "host", peer2Transport, null, peer2State);
        var peer3 = new SolarTurnSession("journal-room", "host", peer3Transport, null, peer3State);
        var peer3CaughtUp = NewSignal<bool>();
        var peer3Snapshots = 0;
        var peer3Mismatches = new List<SolarStateMismatch>();
        var faults = new List<Exception>();

        peer3.ActionCommitted += commit =>
        {
            if (commit.NextTurnIndex == 2) peer3CaughtUp.TrySetResult(true);
        };
        peer3.SnapshotApplied += _ => peer3Snapshots++;
        peer3.StateMismatchDetected += peer3Mismatches.Add;
        host.ProtocolFaulted += faults.Add;
        peer2.ProtocolFaulted += faults.Add;
        peer3.ProtocolFaulted += faults.Add;

        await host.StartAsync().ConfigureAwait(false);
        await peer2.StartAsync().ConfigureAwait(false);
        await peer3.StartAsync().ConfigureAwait(false);
        try
        {
            peer3Transport.DropNextCommitted = true;
            await host.SubmitActionAsync("add", new byte[] { 1 }).ConfigureAwait(false);
            Equal(0, peer3State.Value, "peer3 deliberately missed turn zero");

            await peer2.SubmitActionAsync("add", new byte[] { 1 }).ConfigureAwait(false);
            await WaitAsync(peer3CaughtUp.Task, "journal catch-up").ConfigureAwait(false);

            Equal(2, hostState.Value, "host state after two turns");
            Equal(2, peer3State.Value, "peer3 state after replay catch-up");
            Equal(2L, peer3.KnownNextTurnIndex, "peer3 turn index after replay catch-up");
            Equal(0, peer3Snapshots, "journal replay should avoid snapshot when range is retained");
            Equal(1, peer3Mismatches.Count, "one turn-gap detection");
            Equal(SolarStateMismatchReason.TurnGap, peer3Mismatches[0].Reason, "turn-gap reason");
            Equal(0, faults.Count, "protocol faults during journal replay");
        }
        finally
        {
            await peer3.StopAsync().ConfigureAwait(false);
            await peer2.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static TaskCompletionSource<T> NewSignal<T>()
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
            if (delta > 5) return false;
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

        public void ForceValue(int value)
        {
            Value = value;
        }
    }

    private sealed class DropCommittedTransport : ISolarTransport
    {
        private readonly ISolarTransport _inner;

        public DropCommittedTransport(ISolarTransport inner)
        {
            _inner = inner;
        }

        public bool DropNextCommitted { get; set; }
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

        private async Task OnInnerFrameAsync(SolarFrame frame)
        {
            if (DropNextCommitted)
            {
                var packet = SolarPacketCodec.Decode(frame.Data);
                if (packet.Type == SolarPacketType.TurnCommitted)
                {
                    DropNextCommitted = false;
                    return;
                }
            }

            var handlers = FrameReceived;
            if (handlers == null) return;
            foreach (var handler in handlers.GetInvocationList())
                await ((Func<SolarFrame, Task>)handler)(frame).ConfigureAwait(false);
        }
    }
}
