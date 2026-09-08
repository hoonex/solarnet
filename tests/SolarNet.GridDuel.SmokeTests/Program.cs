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
            RoomLifecycleTransitionsIntoGridDuelMatch,
            ActiveMatchReconnectReplaysMissingTurn
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
        var fixture = await CreateStartedRoomGameAsync("grid-room", "grid-room-game").ConfigureAwait(false);
        try
        {
            await PlayMoonWinAsync(fixture.HostGame, fixture.ClientGame, fixture.HostState, fixture.ClientState).ConfigureAwait(false);
            Equal(SolarRoomPhase.Playing, fixture.HostRoom.Phase, "host room remains playing");
            Equal(SolarRoomPhase.Playing, fixture.ClientRoom.Phase, "client room remains playing");
            Equal(0, fixture.Faults.Count, "room plus game protocol faults");
        }
        finally
        {
            await fixture.DisposeAsync().ConfigureAwait(false);
        }
    }

    private static async Task ActiveMatchReconnectReplaysMissingTurn()
    {
        var fixture = await CreateStartedRoomGameAsync("grid-reconnect-room", "grid-reconnect-game").ConfigureAwait(false);
        try
        {
            await Move(fixture.HostGame, 1, 2).ConfigureAwait(false);
            await Move(fixture.ClientGame, 3, 2).ConfigureAwait(false);
            AssertConverged(fixture.HostState, fixture.ClientState, "before disconnect");
            Equal(2L, fixture.HostGame.KnownNextTurnIndex, "host turn before disconnect");
            Equal(2L, fixture.ClientGame.KnownNextTurnIndex, "client turn before disconnect");

            await fixture.ClientTransport.StopAsync().ConfigureAwait(false);
            await fixture.ClientRoom.NotifyPeerDisconnectedAsync("host").ConfigureAwait(false);
            await fixture.HostRoom.NotifyPeerDisconnectedAsync("client").ConfigureAwait(false);
            Equal(SolarRoomPhase.Reconnecting, fixture.ClientRoom.Phase, "client enters reconnecting phase");
            True(!FindPlayer(fixture.HostRoom.CurrentSnapshot, "client").IsConnected, "host marks client offline");

            await Move(fixture.HostGame, 2, 2).ConfigureAwait(false);
            Equal(3L, fixture.HostGame.KnownNextTurnIndex, "host advances while client is offline");
            Equal(2L, fixture.ClientGame.KnownNextTurnIndex, "client misses offline host turn");
            True(!SnapshotsEqual(fixture.HostState.CaptureSnapshot(), fixture.ClientState.CaptureSnapshot()), "states diverge while client is offline");

            await fixture.ClientTransport.StartAsync().ConfigureAwait(false);
            await fixture.ClientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
            Equal(SolarRoomPhase.Playing, fixture.ClientRoom.Phase, "client rejoins existing playing room");
            True(FindPlayer(fixture.HostRoom.CurrentSnapshot, "client").IsConnected, "host restores existing client slot");

            await fixture.ClientGame.RequestResyncAsync().ConfigureAwait(false);
            Equal(3L, fixture.ClientGame.KnownNextTurnIndex, "client catches up missing turn from host journal");
            AssertConverged(fixture.HostState, fixture.ClientState, "after reconnect resync");

            await Attack(fixture.ClientGame).ConfigureAwait(false);
            Equal(4L, fixture.HostGame.KnownNextTurnIndex, "reconnected client can submit next turn");
            Equal(2, fixture.HostState.GetPlayer("host").Health, "client attack applies after reconnect");
            AssertConverged(fixture.HostState, fixture.ClientState, "after post-reconnect action");
            Equal(0, fixture.Faults.Count, "reconnect protocol faults");
        }
        finally
        {
            await fixture.DisposeAsync().ConfigureAwait(false);
        }
    }

    private static async Task<RoomGameFixture> CreateStartedRoomGameAsync(string roomId, string gameSessionId)
    {
        var fixture = new RoomGameFixture();
        fixture.Hub = new LoopbackTransportHub();
        fixture.HostTransport = fixture.Hub.CreateEndpoint("host");
        fixture.ClientTransport = fixture.Hub.CreateEndpoint("client");
        await fixture.HostTransport.StartAsync().ConfigureAwait(false);
        await fixture.ClientTransport.StartAsync().ConfigureAwait(false);

        fixture.HostRoom = new SolarRoomSession(new SolarRoomOptions(roomId, "host", "SUN", "grid-duel-v1", "Grid Duel", 2), fixture.HostTransport);
        fixture.ClientRoom = new SolarRoomSession(new SolarRoomOptions(roomId, "host", "MOON", "grid-duel-v1", "Grid Duel", 2, SolarHostDisconnectPolicy.WaitForReconnect), fixture.ClientTransport);
        var clientStartSignal = new TaskCompletionSource<SolarGameStartInfo>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.HostRoom.ProtocolFaulted += fixture.Faults.Add;
        fixture.ClientRoom.ProtocolFaulted += fixture.Faults.Add;
        fixture.ClientRoom.GameStarted += info => clientStartSignal.TrySetResult(info);
        fixture.HostRoom.Attach();
        fixture.ClientRoom.Attach();

        await fixture.ClientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
        Equal(SolarRoomPhase.Lobby, fixture.ClientRoom.Phase, "client enters lobby");
        Equal(2, fixture.HostRoom.CurrentSnapshot.Players.Length, "two-player roster");
        await fixture.HostRoom.SetReadyAsync(true).ConfigureAwait(false);
        await fixture.ClientRoom.SetReadyAsync(true).ConfigureAwait(false);
        True(fixture.HostRoom.CanStart, "host can start two ready players");

        var hostStart = await fixture.HostRoom.StartGameAsync(gameSessionId).ConfigureAwait(false);
        var clientStart = await WaitAsync(clientStartSignal.Task, "client Grid Duel start").ConfigureAwait(false);
        Equal(hostStart.GameSessionId, clientStart.GameSessionId, "shared game session id");
        Equal(hostStart.PlayerIds[0], clientStart.PlayerIds[0], "player zero order");
        Equal(hostStart.PlayerIds[1], clientStart.PlayerIds[1], "player one order");

        fixture.HostState = new GridDuelStateMachine(hostStart.PlayerIds[0], hostStart.PlayerIds[1]);
        fixture.ClientState = new GridDuelStateMachine(clientStart.PlayerIds[0], clientStart.PlayerIds[1]);
        fixture.HostGame = new SolarTurnSession(hostStart.GameSessionId, hostStart.HostPeerId, fixture.HostTransport, hostStart.CreateHostTurnCoordinator(), fixture.HostState);
        fixture.ClientGame = new SolarTurnSession(clientStart.GameSessionId, clientStart.HostPeerId, fixture.ClientTransport, null, fixture.ClientState);
        fixture.HostGame.ProtocolFaulted += fixture.Faults.Add;
        fixture.ClientGame.ProtocolFaulted += fixture.Faults.Add;
        await fixture.HostGame.StartAsync().ConfigureAwait(false);
        await fixture.ClientGame.StartAsync().ConfigureAwait(false);
        return fixture;
    }

    private sealed class RoomGameFixture
    {
        public LoopbackTransportHub Hub;
        public LoopbackTransport HostTransport;
        public LoopbackTransport ClientTransport;
        public SolarRoomSession HostRoom;
        public SolarRoomSession ClientRoom;
        public SolarTurnSession HostGame;
        public SolarTurnSession ClientGame;
        public GridDuelStateMachine HostState;
        public GridDuelStateMachine ClientState;
        public readonly List<Exception> Faults = new List<Exception>();

        public async Task DisposeAsync()
        {
            if (HostRoom != null) HostRoom.Detach();
            if (ClientRoom != null) ClientRoom.Detach();
            if (ClientGame != null) await ClientGame.StopAsync().ConfigureAwait(false);
            else if (ClientTransport != null) await ClientTransport.StopAsync().ConfigureAwait(false);
            if (HostGame != null) await HostGame.StopAsync().ConfigureAwait(false);
            else if (HostTransport != null) await HostTransport.StopAsync().ConfigureAwait(false);
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

    private static SolarRoomPlayer FindPlayer(SolarRoomSnapshot snapshot, string peerId)
    {
        if (snapshot == null) throw new Exception("Room snapshot is null while finding " + peerId + ".");
        foreach (var player in snapshot.Players)
            if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player;
        throw new Exception("Room player not found: " + peerId);
    }

    private static void AssertConverged(GridDuelStateMachine host, GridDuelStateMachine client, string label)
    {
        BytesEqual(host.CaptureSnapshot(), client.CaptureSnapshot(), label);
    }

    private static bool SnapshotsEqual(byte[] a, byte[] b)
    {
        if (a == null || b == null || a.Length != b.Length) return false;
        for (var i = 0; i < a.Length; i++) if (a[i] != b[i]) return false;
        return true;
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
