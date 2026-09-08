using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
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
            DesignatedReplicaAckPublishesDurableCommit,
            PendingBarrierRejectsNextTurnWithoutMutation,
            NonDesignatedAckCannotReleaseBarrier,
            SnapshotAckRecoversDroppedCommitAckWithoutDeadlock
        };

        foreach (var test in tests)
        {
            try
            {
                await test().ConfigureAwait(false);
                Console.WriteLine("PASS " + test.Method.Name);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FAIL " + test.Method.Name + ": " + ex);
                return 1;
            }
        }

        Console.WriteLine("Durability barrier smoke tests passed: " + tests.Count + "/" + tests.Count);
        return 0;
    }

    private static async Task DesignatedReplicaAckPublishesDurableCommit()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var replica = hub.CreateEndpoint("client");
        var state = new CounterStateMachine(0);
        var host = new SolarTurnSession(
            "durable-basic",
            "host",
            hostTransport,
            new TurnCoordinator(new[] { "host", "client" }),
            state,
            256,
            "client");
        var committed = 0;
        var durability = Signal<SolarDurabilityAdvance>();
        host.ActionCommitted += _ => committed++;
        host.DurabilityAdvanced += value => durability.TrySetResult(value);

        await host.StartAsync().ConfigureAwait(false);
        await replica.StartAsync().ConfigureAwait(false);
        try
        {
            var submit = host.SubmitActionAsync("add", EncodeInt(5));
            await WaitUntilAsync(() => host.KnownNextTurnIndex == 1 && host.DurabilityPending, "pending durability state").ConfigureAwait(false);

            True(!submit.IsCompleted, "host submit stays incomplete before designated ACK");
            Equal(1L, host.KnownNextTurnIndex, "provisional authoritative frontier");
            Equal(0L, host.DurableNextTurnIndex, "durable frontier before ACK");
            Equal(0, committed, "host commit callback before ACK");
            Equal(5, state.Value, "host reducer already contains provisional state");

            await SendAckAsync(replica, "durable-basic", 1, host.LastStateHash, 0).ConfigureAwait(false);
            await WaitAsync(submit, "host submit after designated ACK").ConfigureAwait(false);
            var advanced = await WaitAsync(durability.Task, "durability advance event").ConfigureAwait(false);

            Equal("client", advanced.ReplicaPeerId, "designated replica identity");
            Equal(1L, advanced.NextTurnIndex, "durable next turn");
            Equal(host.LastStateHash, advanced.StateHash, "durable digest");
            Equal(1L, host.DurableNextTurnIndex, "durable frontier after ACK");
            True(!host.DurabilityPending, "barrier clears after ACK");
            Equal(1, committed, "host publishes commit exactly once after ACK");
        }
        finally
        {
            await replica.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task PendingBarrierRejectsNextTurnWithoutMutation()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var replica = hub.CreateEndpoint("client");
        var state = new CounterStateMachine(0);
        var host = new SolarTurnSession(
            "durable-reject",
            "host",
            hostTransport,
            new TurnCoordinator(new[] { "host", "client" }),
            state,
            256,
            "client");
        var rejection = Signal<SolarTurnRejectReason>();
        replica.FrameReceived += frame =>
        {
            var packet = SolarPacketCodec.Decode(frame.Data);
            if (packet.Type == SolarPacketType.TurnRejected && packet.Payload.Length == 1)
                rejection.TrySetResult((SolarTurnRejectReason)packet.Payload[0]);
            return Task.CompletedTask;
        };

        await host.StartAsync().ConfigureAwait(false);
        await replica.StartAsync().ConfigureAwait(false);
        try
        {
            var first = host.SubmitActionAsync("add", EncodeInt(1));
            await WaitUntilAsync(() => host.DurabilityPending, "first turn durability fence").ConfigureAwait(false);

            var action = new SolarPacket(
                SolarPacketType.TurnAction,
                "durable-reject",
                "client",
                0,
                1,
                EncodeAction("add", EncodeInt(9)));
            await replica.SendAsync("host", SolarPacketCodec.Encode(action)).ConfigureAwait(false);

            Equal(SolarTurnRejectReason.ReplicationPending, await WaitAsync(rejection.Task, "replication-pending rejection").ConfigureAwait(false), "pending rejection reason");
            Equal(1L, host.KnownNextTurnIndex, "pending rejection cannot advance turn");
            Equal(0L, host.DurableNextTurnIndex, "pending rejection cannot advance durability");
            Equal(1, state.Value, "pending rejection cannot mutate reducer state");

            await SendAckAsync(replica, "durable-reject", 1, host.LastStateHash, 1).ConfigureAwait(false);
            await WaitAsync(first, "first durable turn completion").ConfigureAwait(false);
            Equal(1L, host.DurableNextTurnIndex, "first turn becomes durable after ACK");
        }
        finally
        {
            await replica.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task NonDesignatedAckCannotReleaseBarrier()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var replica = hub.CreateEndpoint("replica");
        var other = hub.CreateEndpoint("other");
        var state = new CounterStateMachine(0);
        var host = new SolarTurnSession(
            "durable-designated",
            "host",
            hostTransport,
            new TurnCoordinator(new[] { "host", "replica", "other" }),
            state,
            256,
            "replica");

        await host.StartAsync().ConfigureAwait(false);
        await replica.StartAsync().ConfigureAwait(false);
        await other.StartAsync().ConfigureAwait(false);
        try
        {
            var submit = host.SubmitActionAsync("add", EncodeInt(2));
            await WaitUntilAsync(() => host.DurabilityPending, "designated replica pending state").ConfigureAwait(false);
            var hash = host.LastStateHash;

            await SendAckAsync(other, "durable-designated", 1, hash, 0).ConfigureAwait(false);
            Equal(1L, host.GetReplicationFrontier("other"), "non-designated replication proof still records");
            Equal(0L, host.DurableNextTurnIndex, "non-designated ACK cannot advance durable frontier");
            True(host.DurabilityPending, "non-designated ACK cannot clear barrier");
            True(!submit.IsCompleted, "host submit still waits for designated replica");

            await SendAckAsync(replica, "durable-designated", 1, hash, 0).ConfigureAwait(false);
            await WaitAsync(submit, "designated replica completion").ConfigureAwait(false);
            Equal(1L, host.DurableNextTurnIndex, "designated ACK advances durable frontier");
        }
        finally
        {
            await other.StopAsync().ConfigureAwait(false);
            await replica.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task SnapshotAckRecoversDroppedCommitAckWithoutDeadlock()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientInner = hub.CreateEndpoint("client");
        var clientTransport = new DropFirstReplicationAckTransport(clientInner);
        var hostState = new CounterStateMachine(0);
        var clientState = new CounterStateMachine(0);
        var host = new SolarTurnSession(
            "durable-resync",
            "host",
            hostTransport,
            new TurnCoordinator(new[] { "host", "client" }),
            hostState,
            256,
            "client");
        var client = new SolarTurnSession("durable-resync", "host", clientTransport, null, clientState);
        var snapshots = 0;
        client.SnapshotApplied += _ => snapshots++;

        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            var submit = host.SubmitActionAsync("add", EncodeInt(7));
            await WaitUntilAsync(
                () => clientTransport.DroppedReplicationAck && client.KnownNextTurnIndex == 1 && host.DurabilityPending,
                "dropped ACK pending state").ConfigureAwait(false);

            True(!submit.IsCompleted, "dropped ACK keeps host submit pending");
            Equal(0L, host.DurableNextTurnIndex, "dropped ACK keeps durable frontier behind");
            Equal(7, clientState.Value, "client did apply the provisional commit before ACK loss");

            await client.RequestResyncAsync(1).ConfigureAwait(false);
            await WaitAsync(submit, "snapshot ACK durability recovery").ConfigureAwait(false);

            Equal(1, snapshots, "same-turn resync uses authoritative snapshot");
            Equal(1L, host.DurableNextTurnIndex, "snapshot ACK restores durable frontier");
            True(!host.DurabilityPending, "snapshot ACK clears durability fence");
            Equal(host.LastStateHash, client.LastStateHash, "snapshot recovery digest convergence");
            Equal(7, clientState.Value, "snapshot recovery preserves state");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private sealed class DropFirstReplicationAckTransport : ISolarTransport
    {
        private readonly ISolarTransport _inner;
        private bool _dropped;

        public DropFirstReplicationAckTransport(ISolarTransport inner)
        {
            _inner = inner ?? throw new ArgumentNullException(nameof(inner));
        }

        public string LocalPeerId { get { return _inner.LocalPeerId; } }
        public bool DroppedReplicationAck { get { return _dropped; } }

        public event Func<SolarFrame, Task> FrameReceived
        {
            add { _inner.FrameReceived += value; }
            remove { _inner.FrameReceived -= value; }
        }

        public Task StartAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            return _inner.StartAsync(cancellationToken);
        }

        public Task StopAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            return _inner.StopAsync(cancellationToken);
        }

        public Task SendAsync(string remotePeerId, byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            if (!_dropped)
            {
                var packet = SolarPacketCodec.Decode(frame);
                if (packet.Type == SolarPacketType.ReplicationAck)
                {
                    _dropped = true;
                    return Task.CompletedTask;
                }
            }
            return _inner.SendAsync(remotePeerId, frame, cancellationToken);
        }

        public Task BroadcastAsync(byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            return _inner.BroadcastAsync(frame, cancellationToken);
        }
    }

    private sealed class CounterStateMachine : ISolarGameStateMachine
    {
        public CounterStateMachine(int value) { Value = value; }
        public int Value { get; private set; }

        public bool TryApply(SolarGameAction action)
        {
            if (action == null || !string.Equals(action.ActionKind, "add", StringComparison.Ordinal) || action.Payload.Length != 4)
                return false;
            Value += DecodeInt(action.Payload);
            return true;
        }

        public byte[] CaptureSnapshot() { return EncodeInt(Value); }
        public void RestoreSnapshot(byte[] snapshot) { Value = DecodeInt(snapshot); }
    }

    private static Task SendAckAsync(LoopbackTransport sender, string sessionId, long nextTurnIndex, string stateHash, long sequence)
    {
        var packet = new SolarPacket(
            SolarPacketType.ReplicationAck,
            sessionId,
            sender.LocalPeerId,
            sequence,
            nextTurnIndex,
            Array.Empty<byte>(),
            stateHash);
        return sender.SendAsync("host", SolarPacketCodec.Encode(packet));
    }

    private static byte[] EncodeAction(string actionKind, byte[] payload)
    {
        using (var stream = new MemoryStream())
        using (var writer = new BinaryWriter(stream, Encoding.UTF8))
        {
            WriteString(writer, actionKind);
            writer.Write(payload.Length);
            writer.Write(payload);
            writer.Flush();
            return stream.ToArray();
        }
    }

    private static void WriteString(BinaryWriter writer, string value)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        writer.Write(bytes.Length);
        writer.Write(bytes);
    }

    private static byte[] EncodeInt(int value)
    {
        return new[]
        {
            (byte)((value >> 24) & 0xff),
            (byte)((value >> 16) & 0xff),
            (byte)((value >> 8) & 0xff),
            (byte)(value & 0xff)
        };
    }

    private static int DecodeInt(byte[] bytes)
    {
        if (bytes == null || bytes.Length != 4) throw new InvalidOperationException("Expected one canonical 32-bit integer.");
        return (bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3];
    }

    private static TaskCompletionSource<T> Signal<T>()
    {
        return new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private static async Task WaitAsync(Task task, string label)
    {
        var completed = await Task.WhenAny(task, Task.Delay(3000)).ConfigureAwait(false);
        if (!ReferenceEquals(completed, task)) throw new TimeoutException("Timed out waiting for " + label + ".");
        await task.ConfigureAwait(false);
    }

    private static async Task<T> WaitAsync<T>(Task<T> task, string label)
    {
        var completed = await Task.WhenAny(task, Task.Delay(3000)).ConfigureAwait(false);
        if (!ReferenceEquals(completed, task)) throw new TimeoutException("Timed out waiting for " + label + ".");
        return await task.ConfigureAwait(false);
    }

    private static async Task WaitUntilAsync(Func<bool> predicate, string label)
    {
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(3);
        while (DateTime.UtcNow < deadline)
        {
            if (predicate()) return;
            await Task.Delay(10).ConfigureAwait(false);
        }
        throw new TimeoutException("Timed out waiting for " + label + ".");
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
}
