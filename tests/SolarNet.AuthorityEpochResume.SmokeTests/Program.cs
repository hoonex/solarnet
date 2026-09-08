using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Protocol;
using SolarNet.Room;
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
            PersistedMigratedAuthorityEpochResumesAfterProcessDeath,
            ProvisionalTurnCannotBePersisted,
            CorruptEpochRecordIsRejected,
            WrongPeerCannotResumeAuthority
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

        Console.WriteLine("Authority epoch resume smoke tests passed: " + tests.Count + "/" + tests.Count);
        return 0;
    }

    private static async Task PersistedMigratedAuthorityEpochResumesAfterProcessDeath()
    {
        const string sessionId = "migrated-epoch-2";
        var hub = new LoopbackTransportHub();
        var authorityTransport = hub.CreateEndpoint("client");
        var replicaTransport = hub.CreateEndpoint("host");
        var authorityState = new CounterStateMachine(7);
        var replicaState = new CounterStateMachine(0);
        var coordinator = TurnCoordinator.Restore(
            new[] { "host", "client" },
            2,
            "host",
            2);
        var authority = new SolarTurnSession(
            sessionId,
            "client",
            authorityTransport,
            coordinator,
            authorityState,
            256,
            "host");
        var replica = new SolarTurnSession(sessionId, "client", replicaTransport, null, replicaState);
        var faults = new List<Exception>();
        authority.ProtocolFaulted += faults.Add;
        replica.ProtocolFaulted += faults.Add;

        await authority.StartAsync().ConfigureAwait(false);
        await replica.StartAsync().ConfigureAwait(false);
        SolarAuthorityEpochBootstrap resumed = null;
        LoopbackTransport resumedTransport = null;
        try
        {
            await replica.RequestResyncAsync(0).ConfigureAwait(false);
            await WaitUntilAsync(
                () => replica.KnownNextTurnIndex == 2 && replica.LastStateHash == authority.LastStateHash,
                "initial promoted-state snapshot convergence").ConfigureAwait(false);

            await replica.SubmitActionAsync("add", EncodeInt(2)).ConfigureAwait(false);
            await authority.SubmitActionAsync("add", EncodeInt(3)).ConfigureAwait(false);
            Equal(4L, authority.KnownNextTurnIndex, "pre-crash authoritative turn");
            Equal(4L, authority.DurableNextTurnIndex, "pre-crash durable turn");
            Equal(12, authorityState.Value, "pre-crash authoritative state");
            Equal(12, replicaState.Value, "pre-crash replica state");

            var room = CreateMigratedRoom(sessionId, 17);
            var record = SolarAuthorityEpochPersistence.Capture(room, authority, authorityState);
            var encoded = SolarAuthorityEpochCodec.Encode(record);
            var decoded = SolarAuthorityEpochCodec.Decode(encoded);
            Equal("client", decoded.RoomSnapshot.HostPeerId, "persisted current authority");
            Equal(sessionId, decoded.RoomSnapshot.GameSessionId, "persisted game epoch");
            Equal(4L, decoded.GameCheckpoint.NextTurnIndex, "persisted durable turn");
            Equal("host", decoded.RequiredReplicationPeerId, "persisted designated replica");

            await authority.StopAsync().ConfigureAwait(false);
            resumedTransport = hub.CreateEndpoint("client");
            var resumedState = new CounterStateMachine(0);
            resumed = SolarAuthorityEpochResume.Create(decoded, resumedTransport, resumedState);
            resumed.RoomSession.Attach();
            resumed.GameSession.ProtocolFaulted += faults.Add;
            var preProofRejection = new TaskCompletionSource<SolarTurnRejection>(TaskCreationOptions.RunContinuationsAsynchronously);
            replica.ActionRejected += rejection => preProofRejection.TrySetResult(rejection);
            await resumed.GameSession.StartAsync().ConfigureAwait(false);

            Equal(12, resumedState.Value, "restored canonical state");
            Equal(4L, resumed.GameSession.KnownNextTurnIndex, "restored authoritative turn");
            Equal(4L, resumed.GameSession.DurableNextTurnIndex, "restored durable frontier");
            Equal("host", resumed.GameSession.KnownCurrentPlayerId, "restored active player");
            Equal("host", resumed.GameSession.RequiredReplicationPeerId, "restored required replica");
            Equal("client", resumed.RoomSession.HostPeerId, "restored room authority");
            Equal(sessionId, resumed.RoomSession.CurrentSnapshot.GameSessionId, "restored room game epoch");
            True(FindPlayer(resumed.RoomSession.CurrentSnapshot, "client").IsConnected, "restored authority is online");
            True(!FindPlayer(resumed.RoomSession.CurrentSnapshot, "host").IsConnected, "remote replica starts offline after authority process restart");

            // A persisted durable frontier must itself be sufficient evidence to persist again
            // before an in-memory ACK ledger has been rebuilt in the new process.
            var recaptured = SolarAuthorityEpochPersistence.Capture(
                resumed.RoomSession.CurrentSnapshot,
                resumed.GameSession,
                resumedState);
            Equal(4L, recaptured.GameCheckpoint.NextTurnIndex, "immediate post-resume recapture frontier");
            True(resumed.GameSession.DurabilityRevalidationPending, "restored host requires replica revalidation before accepting turns");

            await replica.SubmitActionAsync("add", EncodeInt(99)).ConfigureAwait(false);
            await WaitAsync(preProofRejection.Task, "pre-proof replication-pending rejection").ConfigureAwait(false);
            var rejected = await preProofRejection.Task.ConfigureAwait(false);
            Equal(SolarTurnRejectReason.ReplicationPending, rejected.Reason, "pre-proof action rejection reason");
            Equal(4L, resumed.GameSession.KnownNextTurnIndex, "pre-proof action cannot advance restored authority");
            Equal(12, resumedState.Value, "pre-proof action cannot mutate restored state");

            await replica.RequestResyncAsync(4).ConfigureAwait(false);
            await WaitUntilAsync(
                () => !resumed.GameSession.DurabilityRevalidationPending && resumed.GameSession.GetReplicationFrontier("host") >= 4,
                "replica proof after restored-host snapshot").ConfigureAwait(false);
            await replica.SubmitActionAsync("add", EncodeInt(4)).ConfigureAwait(false);

            Equal(5L, resumed.GameSession.KnownNextTurnIndex, "restored host continues authority");
            Equal(5L, resumed.GameSession.DurableNextTurnIndex, "continued turn is replicated before submit completes");
            Equal(16, resumedState.Value, "restored host continued state");
            Equal(16, replicaState.Value, "replica converges after restored host continuation");
            Equal(0, faults.Count, "authority epoch resume protocol faults");
        }
        finally
        {
            if (resumed != null) resumed.RoomSession.Detach();
            if (resumed != null) await resumed.GameSession.StopAsync().ConfigureAwait(false);
            else if (resumedTransport != null) await resumedTransport.StopAsync().ConfigureAwait(false);
            await replica.StopAsync().ConfigureAwait(false);
            if (authorityTransport != null)
            {
                try { await authority.StopAsync().ConfigureAwait(false); } catch { }
            }
        }
    }

    private static async Task ProvisionalTurnCannotBePersisted()
    {
        const string sessionId = "pending-persistence";
        var hub = new LoopbackTransportHub();
        var authorityTransport = hub.CreateEndpoint("host");
        var replicaTransport = hub.CreateEndpoint("client");
        var state = new CounterStateMachine(0);
        var authority = new SolarTurnSession(
            sessionId,
            "host",
            authorityTransport,
            new TurnCoordinator(new[] { "host", "client" }),
            state,
            256,
            "client");
        await authority.StartAsync().ConfigureAwait(false);
        await replicaTransport.StartAsync().ConfigureAwait(false);
        try
        {
            var submit = authority.SubmitActionAsync("add", EncodeInt(5));
            await WaitUntilAsync(() => authority.DurabilityPending, "provisional authority turn").ConfigureAwait(false);
            Equal(1L, authority.KnownNextTurnIndex, "provisional known frontier");
            Equal(0L, authority.DurableNextTurnIndex, "provisional durable frontier");

            Throws<InvalidOperationException>(
                () => SolarAuthorityEpochPersistence.Capture(
                    CreateAuthorityRoom(sessionId, "host", 3),
                    authority,
                    state),
                "pending provisional turn must never be persisted as durable authority");

            await SendAckAsync(replicaTransport, sessionId, 1, authority.LastStateHash, 0).ConfigureAwait(false);
            await WaitAsync(submit, "durability ACK completion").ConfigureAwait(false);
            var record = SolarAuthorityEpochPersistence.Capture(
                CreateAuthorityRoom(sessionId, "host", 4),
                authority,
                state);
            Equal(1L, record.GameCheckpoint.NextTurnIndex, "durable turn becomes persistable after ACK");
        }
        finally
        {
            await replicaTransport.StopAsync().ConfigureAwait(false);
            await authority.StopAsync().ConfigureAwait(false);
        }
    }

    private static Task CorruptEpochRecordIsRejected()
    {
        var state = EncodeInt(9);
        var checkpoint = new SolarAuthorityCheckpoint(
            "codec-epoch",
            new[] { "host", "client" },
            0,
            "host",
            1,
            SolarStateDigest.Compute(state),
            state);
        var record = new SolarAuthorityEpochRecord(
            CreateAuthorityRoom("codec-epoch", "host", 1),
            checkpoint,
            "client");
        var encoded = SolarAuthorityEpochCodec.Encode(record);
        encoded[encoded.Length / 2] ^= 0x01;
        Throws<InvalidDataException>(
            () => SolarAuthorityEpochCodec.Decode(encoded),
            "corrupted authority epoch body must fail its record digest");
        return Task.CompletedTask;
    }

    private static Task WrongPeerCannotResumeAuthority()
    {
        var state = EncodeInt(1);
        var checkpoint = new SolarAuthorityCheckpoint(
            "wrong-peer-epoch",
            new[] { "host", "client" },
            0,
            "host",
            1,
            SolarStateDigest.Compute(state),
            state);
        var record = new SolarAuthorityEpochRecord(
            CreateAuthorityRoom("wrong-peer-epoch", "host", 1),
            checkpoint,
            "client");
        var hub = new LoopbackTransportHub();
        var wrongTransport = hub.CreateEndpoint("client");
        Throws<InvalidOperationException>(
            () => SolarAuthorityEpochResume.Create(record, wrongTransport, new CounterStateMachine(0)),
            "non-authority peer cannot claim a persisted authority epoch");
        return Task.CompletedTask;
    }

    private static SolarRoomSnapshot CreateMigratedRoom(string sessionId, long revision)
    {
        return new SolarRoomSnapshot(
            revision,
            "grid-duel-room",
            "Grid Duel",
            "client",
            "grid-duel-v1",
            2,
            SolarRoomPhase.Playing,
            sessionId,
            new[]
            {
                new SolarRoomPlayer(0, "host", "SUN", true, true),
                new SolarRoomPlayer(1, "client", "MOON", true, true)
            });
    }

    private static SolarRoomSnapshot CreateAuthorityRoom(string sessionId, string hostPeerId, long revision)
    {
        return new SolarRoomSnapshot(
            revision,
            "room-" + sessionId,
            "Persistence Room",
            hostPeerId,
            "persistence-v1",
            2,
            SolarRoomPhase.Playing,
            sessionId,
            new[]
            {
                new SolarRoomPlayer(0, "host", "SUN", true, true),
                new SolarRoomPlayer(1, "client", "MOON", true, true)
            });
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

    private static SolarRoomPlayer FindPlayer(SolarRoomSnapshot snapshot, string peerId)
    {
        foreach (var player in snapshot.Players)
            if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player;
        throw new Exception("Room player not found: " + peerId);
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

    private static async Task WaitAsync(Task task, string label)
    {
        var completed = await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(4))).ConfigureAwait(false);
        if (!ReferenceEquals(completed, task)) throw new TimeoutException("Timed out waiting for " + label + ".");
        await task.ConfigureAwait(false);
    }

    private static async Task WaitUntilAsync(Func<bool> predicate, string label)
    {
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(4);
        while (DateTime.UtcNow < deadline)
        {
            if (predicate()) return;
            await Task.Delay(10).ConfigureAwait(false);
        }
        throw new TimeoutException("Timed out waiting for " + label + ".");
    }

    private static void Throws<TException>(Action action, string label) where TException : Exception
    {
        try { action(); }
        catch (TException) { return; }
        throw new Exception("Expected " + typeof(TException).Name + ": " + label);
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
