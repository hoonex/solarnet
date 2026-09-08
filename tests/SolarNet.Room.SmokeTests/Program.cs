using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.Nearby;
using SolarNet.Room;
using SolarNet.Session;
using SolarNet.Transport;
using SolarNet.Turns;

internal static class Program
{
    private static async Task<int> Main()
    {
        var tests = new List<Func<Task>>
        {
            TwoPlayersJoinReadyStartAndShareTransportWithGame,
            CompatibilityMismatchRejectsWithoutRosterMutation,
            WaitForReconnectPolicyRejoinsSameHost
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

        Console.WriteLine("SolarNet room smoke tests passed: " + passed + "/" + tests.Count);
        return 0;
    }

    private static async Task TwoPlayersJoinReadyStartAndShareTransportWithGame()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientTransport = hub.CreateEndpoint("client");
        await hostTransport.StartAsync().ConfigureAwait(false);
        await clientTransport.StartAsync().ConfigureAwait(false);

        var hostRoom = new SolarRoomSession(
            new SolarRoomOptions("room-a", "host", "Host", "game-v1", "Lunch Break", 4),
            hostTransport);
        var clientRoom = new SolarRoomSession(
            new SolarRoomOptions("room-a", "host", "Client", "game-v1", "Lunch Break", 4),
            clientTransport);
        var clientJoined = Signal<SolarRoomSnapshot>();
        var clientStarted = Signal<SolarGameStartInfo>();
        var faults = new List<Exception>();

        clientRoom.RoomChanged += snapshot =>
        {
            if (snapshot.Players.Length == 2 && snapshot.Phase == SolarRoomPhase.Lobby) clientJoined.TrySetResult(snapshot);
        };
        clientRoom.GameStarted += clientStarted.TrySetResult;
        hostRoom.ProtocolFaulted += faults.Add;
        clientRoom.ProtocolFaulted += faults.Add;
        hostRoom.Attach();
        clientRoom.Attach();

        SolarTurnSession hostGame = null;
        SolarTurnSession clientGame = null;
        try
        {
            await clientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
            var joined = await WaitAsync(clientJoined.Task, "client room join").ConfigureAwait(false);
            Equal(2, joined.Players.Length, "joined roster size");
            Equal("host", joined.Players[0].PeerId, "host keeps slot zero");

            await hostRoom.SetReadyAsync(true).ConfigureAwait(false);
            await clientRoom.SetReadyAsync(true).ConfigureAwait(false);
            True(hostRoom.CanStart, "host can start after both players are ready");

            var hostStart = await hostRoom.StartGameAsync("game-session-a").ConfigureAwait(false);
            var clientStart = await WaitAsync(clientStarted.Task, "client game start").ConfigureAwait(false);
            Equal(hostStart.GameSessionId, clientStart.GameSessionId, "shared game session id");
            Equal("host", clientStart.PlayerIds[0], "ordered player zero");
            Equal("client", clientStart.PlayerIds[1], "ordered player one");

            var hostCommits = 0;
            var clientCommits = 0;
            hostGame = new SolarTurnSession(hostStart.GameSessionId, "host", hostTransport, hostStart.CreateHostTurnCoordinator());
            clientGame = new SolarTurnSession(clientStart.GameSessionId, "host", clientTransport);
            hostGame.ActionCommitted += _ => hostCommits++;
            clientGame.ActionCommitted += _ => clientCommits++;
            await hostGame.StartAsync().ConfigureAwait(false);
            await clientGame.StartAsync().ConfigureAwait(false);
            await hostGame.SubmitActionAsync(0, "move", new byte[] { 1 }).ConfigureAwait(false);

            Equal(1, hostCommits, "host game commit over room transport");
            Equal(1, clientCommits, "client game commit over room transport");
            Equal(SolarRoomPhase.Playing, hostRoom.Phase, "host room remains playing while game packets flow");
            Equal(SolarRoomPhase.Playing, clientRoom.Phase, "client room ignores game-session packets");
            Equal(0, faults.Count, "room protocol faults");
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

    private static async Task CompatibilityMismatchRejectsWithoutRosterMutation()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientTransport = hub.CreateEndpoint("client");
        await hostTransport.StartAsync().ConfigureAwait(false);
        await clientTransport.StartAsync().ConfigureAwait(false);

        var hostRoom = new SolarRoomSession(new SolarRoomOptions("room-b", "host", "Host", "build-A"), hostTransport);
        var clientRoom = new SolarRoomSession(new SolarRoomOptions("room-b", "host", "Client", "build-B"), clientTransport);
        var rejected = Signal<SolarRoomJoinRejection>();
        clientRoom.JoinRejected += rejected.TrySetResult;
        hostRoom.Attach();
        clientRoom.Attach();

        try
        {
            await clientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
            var rejection = await WaitAsync(rejected.Task, "compatibility rejection").ConfigureAwait(false);
            Equal(SolarRoomJoinRejectReason.CompatibilityMismatch, rejection.Reason, "compatibility rejection reason");
            Equal(1, hostRoom.CurrentSnapshot.Players.Length, "host roster unchanged after rejection");
            Equal(SolarRoomPhase.Closed, clientRoom.Phase, "rejected room session closes");
        }
        finally
        {
            hostRoom.Detach();
            clientRoom.Detach();
            await clientTransport.StopAsync().ConfigureAwait(false);
            await hostTransport.StopAsync().ConfigureAwait(false);
        }
    }

    private static async Task WaitForReconnectPolicyRejoinsSameHost()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint("host");
        var clientTransport = hub.CreateEndpoint("client");
        await hostTransport.StartAsync().ConfigureAwait(false);
        await clientTransport.StartAsync().ConfigureAwait(false);

        var hostRoom = new SolarRoomSession(new SolarRoomOptions("room-c", "host", "Host", "game-v1"), hostTransport);
        var clientRoom = new SolarRoomSession(
            new SolarRoomOptions("room-c", "host", "Client", "game-v1", hostDisconnectPolicy: SolarHostDisconnectPolicy.WaitForReconnect),
            clientTransport);
        hostRoom.Attach();
        clientRoom.Attach();

        try
        {
            await clientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
            Equal(SolarRoomPhase.Lobby, clientRoom.Phase, "client joined before disconnect");

            await clientRoom.NotifyPeerDisconnectedAsync("host").ConfigureAwait(false);
            Equal(SolarRoomPhase.Reconnecting, clientRoom.Phase, "explicit wait-for-reconnect phase");

            await clientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
            Equal(SolarRoomPhase.Lobby, clientRoom.Phase, "same host peer rejoins room after reconnect");
            Equal(2, hostRoom.CurrentSnapshot.Players.Length, "reconnect does not duplicate roster identity");
        }
        finally
        {
            hostRoom.Detach();
            clientRoom.Detach();
            await clientTransport.StopAsync().ConfigureAwait(false);
            await hostTransport.StopAsync().ConfigureAwait(false);
        }
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
}
