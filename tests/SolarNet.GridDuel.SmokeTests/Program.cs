using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.Samples.GridDuel;
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
            SnapshotIsCanonicalAndRestorable,
            InvalidGameActionDoesNotAdvanceTurn,
            NetworkedMatchConvergesAndProducesWinner
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
        Console.WriteLine("Grid Duel smoke tests passed: " + passed + "/" + tests.Count);
        return 0;
    }

    private static Task SnapshotIsCanonicalAndRestorable()
    {
        var a = new GridDuelStateMachine("sun", "moon");
        var b = new GridDuelStateMachine("sun", "moon");
        BytesEqual(a.CaptureSnapshot(), b.CaptureSnapshot(), "equal initial snapshots");

        True(a.TryApply(new SolarGameAction("sun", 0, GridDuelActionCodec.MoveAction, GridDuelActionCodec.EncodeMove(1, 2))), "apply move before snapshot");
        var moved = a.CaptureSnapshot();
        b.RestoreSnapshot(moved);
        BytesEqual(moved, b.CaptureSnapshot(), "snapshot roundtrip");
        Equal(1, b.GetPlayer("sun").X, "restored X");
        return Task.CompletedTask;
    }

    private static async Task InvalidGameActionDoesNotAdvanceTurn()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("sun");
        var clientTransport = hub.CreateEndpoint("moon");
        var hostState = new GridDuelStateMachine("sun", "moon");
        var clientState = new GridDuelStateMachine("sun", "moon");
        var host = new SolarTurnSession("grid-invalid", "sun", hostTransport, new TurnCoordinator(new[] { "sun", "moon" }), hostState);
        var client = new SolarTurnSession("grid-invalid", "sun", clientTransport, null, clientState);
        SolarTurnRejection rejection = null;
        host.ActionRejected += value => rejection = value;
        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            var before = hostState.CaptureSnapshot();
            await host.SubmitActionAsync(GridDuelActionCodec.MoveAction, GridDuelActionCodec.EncodeMove(2, 2)).ConfigureAwait(false);
            True(rejection != null, "invalid move should be rejected");
            Equal(SolarTurnRejectReason.InvalidAction, rejection.Reason, "invalid action rejection reason");
            Equal(0L, host.KnownNextTurnIndex, "invalid action does not advance turn");
            BytesEqual(before, hostState.CaptureSnapshot(), "invalid action leaves host state unchanged");
            BytesEqual(before, clientState.CaptureSnapshot(), "invalid action leaves client state unchanged");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task NetworkedMatchConvergesAndProducesWinner()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("sun");
        var clientTransport = hub.CreateEndpoint("moon");
        var hostState = new GridDuelStateMachine("sun", "moon");
        var clientState = new GridDuelStateMachine("sun", "moon");
        var host = new SolarTurnSession("grid-match", "sun", hostTransport, new TurnCoordinator(new[] { "sun", "moon" }), hostState);
        var client = new SolarTurnSession("grid-match", "sun", clientTransport, null, clientState);
        var faults = new List<Exception>();
        host.ProtocolFaulted += faults.Add;
        client.ProtocolFaulted += faults.Add;
        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            await Move(host, 1, 2).ConfigureAwait(false);
            AssertConverged(hostState, clientState, "after sun move 1");
            await Move(client, 3, 2).ConfigureAwait(false);
            AssertConverged(hostState, clientState, "after moon move 1");
            await Move(host, 2, 2).ConfigureAwait(false);
            AssertConverged(hostState, clientState, "after sun closes distance");
            await Attack(client).ConfigureAwait(false);
            await Attack(host).ConfigureAwait(false);
            await Attack(client).ConfigureAwait(false);
            await Attack(host).ConfigureAwait(false);
            await Attack(client).ConfigureAwait(false);

            AssertConverged(hostState, clientState, "at match end");
            Equal("moon", hostState.WinnerPeerId, "winner");
            Equal(0, hostState.GetPlayer("sun").Health, "defeated health");
            Equal(0L, host.KnownNextTurnIndex - client.KnownNextTurnIndex, "turn indices converge");
            Equal(0, faults.Count, "protocol faults");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static Task Move(SolarTurnSession session, int x, int y)
    {
        return session.SubmitActionAsync(GridDuelActionCodec.MoveAction, GridDuelActionCodec.EncodeMove(x, y));
    }

    private static Task Attack(SolarTurnSession session)
    {
        return session.SubmitActionAsync(GridDuelActionCodec.AttackAction, Array.Empty<byte>());
    }

    private static void AssertConverged(GridDuelStateMachine host, GridDuelStateMachine client, string label)
    {
        BytesEqual(host.CaptureSnapshot(), client.CaptureSnapshot(), label);
    }

    private static void BytesEqual(byte[] expected, byte[] actual, string label)
    {
        Equal(expected.Length, actual.Length, label + " length");
        for (var i = 0; i < expected.Length; i++)
            if (expected[i] != actual[i]) throw new Exception(label + " differs at byte " + i + ".");
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
