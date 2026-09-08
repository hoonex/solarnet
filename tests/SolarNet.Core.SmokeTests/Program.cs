using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using SolarNet.Protocol;
using SolarNet.Session;
using SolarNet.Transport;
using SolarNet.Turns;

internal static class Program
{
    private static async Task<int> Main()
    {
        var tests = new List<Func<Task>>
        {
            PacketRoundTrip,
            CoordinatorRejectsWrongPlayerAndDuplicateSequence,
            EndToEndHostClientTurns
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

        Console.WriteLine("SolarNet smoke tests passed: " + passed + "/" + tests.Count);
        return 0;
    }

    private static Task PacketRoundTrip()
    {
        var original = new SolarPacket(SolarPacketType.TurnAction, "session-a", "peer-a", 12, 7, new byte[] { 1, 2, 3 }, "abc123");
        var decoded = SolarPacketCodec.Decode(SolarPacketCodec.Encode(original));

        Equal(original.Type, decoded.Type, "packet type");
        Equal(original.SessionId, decoded.SessionId, "session id");
        Equal(original.SenderId, decoded.SenderId, "sender id");
        Equal(original.Sequence, decoded.Sequence, "sequence");
        Equal(original.TurnIndex, decoded.TurnIndex, "turn index");
        Equal(original.StateHash, decoded.StateHash, "state hash");
        Equal(3, decoded.Payload.Length, "payload length");
        Equal((byte)2, decoded.Payload[1], "payload byte");

        var corrupt = SolarPacketCodec.Encode(original);
        corrupt[0] = 0;
        Throws<InvalidDataException>(() => SolarPacketCodec.Decode(corrupt), "magic guard");
        return Task.CompletedTask;
    }

    private static Task CoordinatorRejectsWrongPlayerAndDuplicateSequence()
    {
        var coordinator = new TurnCoordinator(new[] { "host", "peer-2" });
        SolarTurnCommit commit;
        SolarTurnRejectReason reason;

        False(coordinator.TryCommit(new SolarTurnAction("peer-2", 0, 0, "attack", Array.Empty<byte>()), out commit, out reason), "wrong player should reject");
        Equal(SolarTurnRejectReason.NotActivePlayer, reason, "wrong player reason");

        True(coordinator.TryCommit(new SolarTurnAction("host", 0, 5, "move", new byte[] { 9 }), out commit, out reason), "host first turn");
        Equal(1L, coordinator.TurnIndex, "turn advances");
        Equal("peer-2", coordinator.CurrentPlayerId, "active player advances");

        True(coordinator.TryCommit(new SolarTurnAction("peer-2", 1, 8, "attack", Array.Empty<byte>()), out commit, out reason), "peer second turn");
        Equal(2, coordinator.Round, "round increments after wrap");

        False(coordinator.TryCommit(new SolarTurnAction("host", 2, 5, "move", Array.Empty<byte>()), out commit, out reason), "duplicate accepted sequence should reject");
        Equal(SolarTurnRejectReason.DuplicateOrOutOfOrderSequence, reason, "duplicate sequence reason");
        return Task.CompletedTask;
    }

    private static async Task EndToEndHostClientTurns()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientTransport = hub.CreateEndpoint("peer-2");
        var coordinator = new TurnCoordinator(new[] { "host", "peer-2" });
        var host = new SolarTurnSession("room-1", "host", hostTransport, coordinator);
        var client = new SolarTurnSession("room-1", "host", clientTransport);

        var hostCommits = new List<SolarTurnCommit>();
        var clientCommits = new List<SolarTurnCommit>();
        var clientRejections = new List<SolarTurnRejection>();
        var faults = new List<Exception>();

        host.ActionCommitted += hostCommits.Add;
        client.ActionCommitted += clientCommits.Add;
        client.ActionRejected += clientRejections.Add;
        host.ProtocolFaulted += faults.Add;
        client.ProtocolFaulted += faults.Add;

        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            await host.SubmitActionAsync(0, "move", new byte[] { 10 }).ConfigureAwait(false);
            Equal(1, hostCommits.Count, "host sees local commit");
            Equal(1, clientCommits.Count, "client receives host commit");
            Equal("peer-2", clientCommits[0].NextPlayerId, "client sees next player");

            await client.SubmitActionAsync(1, "attack", new byte[] { 20 }).ConfigureAwait(false);
            Equal(2, hostCommits.Count, "host receives and commits client action");
            Equal(2, clientCommits.Count, "client receives authoritative echo commit");
            Equal("peer-2", clientCommits[1].ActorId, "client commit actor");
            Equal(2L, clientCommits[1].NextTurnIndex, "next turn index");

            await client.SubmitActionAsync(2, "illegal-now", Array.Empty<byte>()).ConfigureAwait(false);
            Equal(1, clientRejections.Count, "client gets rejection");
            Equal(SolarTurnRejectReason.NotActivePlayer, clientRejections[0].Reason, "rejection reason");
            Equal(0, faults.Count, "no protocol faults");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static void True(bool value, string label)
    {
        if (!value) throw new Exception("Expected true: " + label);
    }

    private static void False(bool value, string label)
    {
        if (value) throw new Exception("Expected false: " + label);
    }

    private static void Equal<T>(T expected, T actual, string label)
    {
        if (!EqualityComparer<T>.Default.Equals(expected, actual))
            throw new Exception(label + " expected <" + expected + "> but got <" + actual + ">.");
    }

    private static void Throws<T>(Action action, string label) where T : Exception
    {
        try
        {
            action();
        }
        catch (T)
        {
            return;
        }
        throw new Exception("Expected " + typeof(T).Name + ": " + label);
    }
}
