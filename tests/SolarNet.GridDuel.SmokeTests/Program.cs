using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.Room;
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
            NetworkedMatchConvergesAndProducesWinner,
            RoomLifecycleTransitionsIntoGridDuelMatch
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
            Equal(SolarTurnRejectReason.GameRuleRejected, rejection.Reason, "invalid game-rule rejection reason");
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
            await PlayMoonWinAsync(host, client, hostState, clientState).ConfigureAwait(false);
            Equal(0, faults.Count, "protocol faults");
        }
        finally
        {
            await client.StopAsync().ConfigureAwait(false);
            await host.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task RoomLifecycleTransitionsIntoGridDuelMatch()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientTransport = hub.CreateEndpoint("client");
        await hostTransport.StartAsync().ConfigureAwait(false);
        await clientTransport.StartAsync().ConfigureAwait(false);

        var hostRoom = new SolarRoomSession(new SolarRoomOptions("grid-room", "host", "SUN", "grid-duel-v1", "Grid Duel", 2), hostTransport);
        var clientRoom = new SolarRoomSession(new SolarRoomOptions("grid-room", "host", "MOON", "grid-duel-v1", "Grid Duel", 2), clientTransport);
        var clientStartSignal = new TaskCompletionSource<SolarGameStartInfo>(TaskCreationOptions.RunContinuationsAsynchronously);
        var faults = new List<Exception>();
        hostRoom.ProtocolFaulted += faults.Add;
        clientRoom.ProtocolFaulted += faults.Add;
        clientRoom.GameStarted += info => clientStartSignal.TrySetResult(info);
        hostRoom.Attach();
        clientRoom.Attach();

        SolarTurnSession hostGame = null;
        SolarTurnSession clientGame = null;
        try
        {
            await clientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
            Equal(SolarRoomPhase.Lobby, clientRoom.Phase, "client enters lobby");
            Equal(2, hostRoom.CurrentSnapshot.Players.Length, "two-player roster");

            await hostRoom.SetReadyAsync(true).ConfigureAwait(false);
            await clientRoom.SetReadyAsync(true).ConfigureAwait(false);
            True(hostRoom.CanStart, "host can start two ready players");

            var hostStart = await hostRoom.StartGameAsync("grid-room-game").ConfigureAwait(false);
            var clientStart = await WaitAsync(clientStartSignal.Task, "client Grid Duel start").ConfigureAwait(false);
            Equal(hostStart.GameSessionId, clientStart.GameSessionId, "shared game session id");
            Equal(2, hostStart.PlayerIds.Length, "exactly two game players");
            Equal(hostStart.PlayerIds[0], clientStart.PlayerIds[0], "player zero order");
            Equal(hostStart.PlayerIds[1], clientStart.PlayerIds[1], "player one order");

            var hostState = new GridDuelStateMachine(hostStart.PlayerIds[0], hostStart.PlayerIds[1]);
            var clientState = new GridDuelStateMachine(clientStart.PlayerIds[0], clientStart.PlayerIds[1]);
            hostGame = new SolarTurnSession(hostStart.GameSessionId, hostStart.HostPeerId, hostTransport, hostStart.CreateHostTurnCoordinator(), hostState);
            clientGame = new SolarTurnSession(clientStart.GameSessionId, clientStart.HostPeerId, clientTransport, null, clientState);
            hostGame.ProtocolFaulted += faults.Add;
            clientGame.ProtocolFaulted += faults.Add;
            await hostGame.StartAsync().ConfigureAwait(false);
            await clientGame.StartAsync().ConfigureAwait(false);

            await PlayMoonWinAsync(hostGame, clientGame, hostState, clientState).ConfigureAwait(false);
            Equal(SolarRoomPhase.Playing, hostRoom.Phase, "host room remains playing");
            Equal(SolarRoomPhase.Playing, clientRoom.Phase, "client room remains playing");
            Equal(0, faults.Count, "room plus game protocol faults");
        }
        finally
        {
            hostRoom.Detach();
            clientRoom.Detach();
            if (clientGame != null) await clientGame.StopAsync().ConfigureAwait(false);
            else await clientTransport.StopAsync().ConfigureAwait(false);
            if (hostGame != null) await hostGame.StopAsync().ConfigureAwait(false);
            else await hostTransport.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task PlayMoonWinAsync(SolarTurnSession host, SolarTurnSession client, GridDuelStateMachine hostState, GridDuelStateMachine clientState)
    {
        await Move(host, 1, 2).ConfigureAwait(false);
        AssertConverged(hostState, clientState, "after first player move");
        await Move(client, 3, 2).ConfigureAwait(false);
        AssertConverged(hostState, clientState, "after second player move");
        await Move(host, 2, 2).ConfigureAwait(false);
        AssertConverged(hostState, clientState, "after players become adjacent");
        await Attack(client).ConfigureAwait(false);
        await Attack(host).ConfigureAwait(false);
        await Attack(client).ConfigureAwait(false);
        await Attack(host).ConfigureAwait(false);
        await Attack(client).ConfigureAwait(false);

        AssertConverged(hostState, clientState, "at match end");
        Equal(client.LocalPeerId, hostState.WinnerPeerId, "second player wins scripted match");
        Equal(0, hostState.GetPlayer(host.LocalPeerId).Health, "first player defeated");
        Equal(0L, host.KnownNextTurnIndex - client.KnownNextTurnIndex, "turn indices converge");
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

    private static async Task<T> WaitAsync<T>(Task<T> task, string label)
    {
        var completed = await Task.WhenAny(task, Task.Delay(3000)).ConfigureAwait(false);
        if (!ReferenceEquals(completed, task)) throw new TimeoutException("Timed out waiting for " + label + ".");
        return await task.ConfigureAwait(false);
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
