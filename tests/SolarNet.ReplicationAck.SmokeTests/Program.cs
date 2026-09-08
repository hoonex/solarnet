using System;
using System.Collections.Generic;
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
            CommitAckAdvancesReplicationFrontier,
            SnapshotAckAdvancesReplicationFrontier,
            DuplicateAckIsIdempotent,
            ForgedDigestDoesNotAdvanceFrontier
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

        Console.WriteLine("Replication acknowledgement smoke tests passed: " + tests.Count + "/" + tests.Count);
        return 0;
    }

    private static async Task CommitAckAdvancesReplicationFrontier()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientTransport = hub.CreateEndpoint("client");
        var hostState = new CounterStateMachine(0);
        var clientState = new CounterStateMachine(0);
        var host = new SolarTurnSession("ack-commit", "host", hostTransport, new TurnCoordinator(new[] { "host", "client" }), hostState);
        var client = new SolarTurnSession("ack-commit", "host", clientTransport, null, clientState);
        var acknowledged = Signal<SolarReplicationAcknowledgement>();
        var failures = new List<Exception>();
        host.ReplicationAcknowledged += value => acknowledged.TrySetResult(value);
        client.ReplicationAcknowledgementFailed += failures.Add;

        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            await host.SubmitActionAsync("add", EncodeInt(5)).ConfigureAwait(false);
            var ack = await WaitAsync(acknowledged.Task, "commit replication acknowledgement").ConfigureAwait(false);
            Equal("client", ack.PeerId, "ack peer");
            Equal(1L, ack.NextTurnIndex, "ack next-turn frontier");
            Equal(host.LastStateHash, ack.StateHash, "ack state hash");
            Equal(1L, host.GetReplicationFrontier("client"), "host replication frontier");
            True(host.IsReplicatedThrough("client", 1), "host proves client replicated turn zero");
            Equal(5, clientState.Value, "client reducer state");
            Equal(0, failures.Count, "ack send failures");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task SnapshotAckAdvancesReplicationFrontier()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientTransport = hub.CreateEndpoint("client");
        var hostState = new CounterStateMachine(7);
        var clientState = new CounterStateMachine(0);
        var coordinator = TurnCoordinator.Restore(new[] { "host", "client" }, 3, "client", 2);
        var host = new SolarTurnSession("ack-snapshot", "host", hostTransport, coordinator, hostState);
        var client = new SolarTurnSession("ack-snapshot", "host", clientTransport, null, clientState);
        var acknowledged = Signal<SolarReplicationAcknowledgement>();
        var snapshots = 0;
        host.ReplicationAcknowledged += value => acknowledged.TrySetResult(value);
        client.SnapshotApplied += _ => snapshots++;

        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            await client.RequestResyncAsync(0).ConfigureAwait(false);
            var ack = await WaitAsync(acknowledged.Task, "snapshot replication acknowledgement").ConfigureAwait(false);
            Equal(1, snapshots, "snapshot application count");
            Equal(3L, ack.NextTurnIndex, "snapshot acknowledgement frontier");
            Equal(host.LastStateHash, ack.StateHash, "snapshot acknowledgement hash");
            Equal(3L, host.GetReplicationFrontier("client"), "snapshot advances host proof frontier");
            Equal(3L, client.KnownNextTurnIndex, "client restored next turn");
            Equal(7, clientState.Value, "client restored state");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task DuplicateAckIsIdempotent()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var rawClient = hub.CreateEndpoint("client");
        var hostState = new CounterStateMachine(0);
        var host = new SolarTurnSession("ack-duplicate", "host", hostTransport, new TurnCoordinator(new[] { "host", "client" }), hostState);
        var ackEvents = 0;
        host.ReplicationAcknowledged += _ => ackEvents++;

        await host.StartAsync().ConfigureAwait(false);
        await rawClient.StartAsync().ConfigureAwait(false);
        try
        {
            await host.SubmitActionAsync("add", EncodeInt(1)).ConfigureAwait(false);
            var hash = host.LastStateHash;
            await SendAckAsync(rawClient, "ack-duplicate", 1, hash, 0).ConfigureAwait(false);
            await SendAckAsync(rawClient, "ack-duplicate", 1, hash, 1).ConfigureAwait(false);
            Equal(1L, host.GetReplicationFrontier("client"), "duplicate acknowledgement frontier");
            Equal(1, ackEvents, "duplicate acknowledgement event count");
        }
        finally
        {
            await rawClient.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task ForgedDigestDoesNotAdvanceFrontier()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var rawClient = hub.CreateEndpoint("client");
        var hostState = new CounterStateMachine(0);
        var host = new SolarTurnSession("ack-forged", "host", hostTransport, new TurnCoordinator(new[] { "host", "client" }), hostState);
        var faulted = Signal<Exception>();
        host.ProtocolFaulted += ex => faulted.TrySetResult(ex);

        await host.StartAsync().ConfigureAwait(false);
        await rawClient.StartAsync().ConfigureAwait(false);
        try
        {
            await host.SubmitActionAsync("add", EncodeInt(3)).ConfigureAwait(false);
            await SendAckAsync(rawClient, "ack-forged", 1, new string('0', 64), 0).ConfigureAwait(false);
            var fault = await WaitAsync(faulted.Task, "forged acknowledgement protocol fault").ConfigureAwait(false);
            True(fault.Message.IndexOf("digest", StringComparison.OrdinalIgnoreCase) >= 0, "forged acknowledgement reports digest mismatch");
            Equal(0L, host.GetReplicationFrontier("client"), "forged acknowledgement cannot advance proof frontier");
            True(!host.IsReplicatedThrough("client", 1), "forged acknowledgement is not migration-safe proof");
        }
        finally
        {
            await rawClient.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
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

    private static async Task<T> WaitAsync<T>(Task<T> task, string label)
    {
        var completed = await Task.WhenAny(task, Task.Delay(3000)).ConfigureAwait(false);
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
}
