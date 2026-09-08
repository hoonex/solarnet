using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.Room;
using SolarNet.Samples.GridDuel;
using SolarNet.Session;
using SolarNet.State;
using SolarNet.Transport;

internal static class Program
{
    private const string HostPeerId = "host";
    private const string ClientPeerId = "client-stable";
    private const string RoomId = "process-resume-room";
    private const string GameSessionId = "process-resume-game";

    private static async Task<int> Main()
    {
        try
        {
            await ClientProcessDeathResumesActiveMatch().ConfigureAwait(false);
            Console.WriteLine("PASS ClientProcessDeathResumesActiveMatch");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("FAIL ClientProcessDeathResumesActiveMatch: " + ex);
            return 1;
        }
    }

    private static async Task ClientProcessDeathResumesActiveMatch()
    {
        var hub = new LoopbackTransportHub();
        var hostTransport = hub.CreateEndpoint(HostPeerId);
        var initialClientTransport = hub.CreateEndpoint(ClientPeerId);
        await hostTransport.StartAsync().ConfigureAwait(false);
        await initialClientTransport.StartAsync().ConfigureAwait(false);

        var hostRoom = CreateRoom(HostPeerId, "SUN", hostTransport);
        var initialClientRoom = CreateRoom(HostPeerId, "MOON", initialClientTransport);
        var initialStartSignal = Signal<SolarGameStartInfo>();
        var faults = new List<Exception>();
        initialClientRoom.GameStarted += info => initialStartSignal.TrySetResult(info);
        hostRoom.ProtocolFaulted += faults.Add;
        initialClientRoom.ProtocolFaulted += faults.Add;
        hostRoom.Attach();
        initialClientRoom.Attach();

        SolarTurnSession hostGame = null;
        SolarTurnSession initialClientGame = null;
        SolarRoomSession resumedRoom = null;
        SolarTurnSession resumedGame = null;
        LoopbackTransport resumedTransport = null;

        try
        {
            await initialClientRoom.NotifyPeerConnectedAsync(HostPeerId).ConfigureAwait(false);
            Equal(SolarRoomPhase.Lobby, initialClientRoom.Phase, "initial client joins lobby");
            Equal(2, hostRoom.CurrentSnapshot.Players.Length, "initial two-player roster");

            await hostRoom.SetReadyAsync(true).ConfigureAwait(false);
            await initialClientRoom.SetReadyAsync(true).ConfigureAwait(false);
            True(hostRoom.CanStart, "host can start initial match");

            var hostStart = await hostRoom.StartGameAsync(GameSessionId).ConfigureAwait(false);
            var initialStart = await WaitAsync(initialStartSignal.Task, "initial client game start").ConfigureAwait(false);
            Equal(GameSessionId, initialStart.GameSessionId, "initial game session id");

            var hostState = new GridDuelStateMachine(hostStart.PlayerIds[0], hostStart.PlayerIds[1]);
            var initialClientState = new GridDuelStateMachine(initialStart.PlayerIds[0], initialStart.PlayerIds[1]);
            hostGame = new SolarTurnSession(
                hostStart.GameSessionId,
                hostStart.HostPeerId,
                hostTransport,
                hostStart.CreateHostTurnCoordinator(),
                hostState,
                journalCapacity: 1);
            initialClientGame = new SolarTurnSession(
                initialStart.GameSessionId,
                initialStart.HostPeerId,
                initialClientTransport,
                null,
                initialClientState);
            hostGame.ProtocolFaulted += faults.Add;
            initialClientGame.ProtocolFaulted += faults.Add;
            await hostGame.StartAsync().ConfigureAwait(false);
            await initialClientGame.StartAsync().ConfigureAwait(false);

            await Move(hostGame, 1, 2).ConfigureAwait(false);
            await Move(initialClientGame, 3, 2).ConfigureAwait(false);
            await Move(hostGame, 2, 2).ConfigureAwait(false);
            AssertConverged(hostState, initialClientState, "before process death");
            Equal(3L, hostGame.KnownNextTurnIndex, "turn before process death");
            Equal(ClientPeerId, hostGame.KnownCurrentPlayerId, "client owns next turn before process death");

            var originalClientPlayer = FindPlayer(hostRoom.CurrentSnapshot, ClientPeerId);
            var originalSlot = originalClientPlayer.Slot;

            initialClientRoom.Detach();
            await initialClientGame.StopAsync().ConfigureAwait(false);
            initialClientGame = null;
            await hostRoom.NotifyPeerDisconnectedAsync(ClientPeerId).ConfigureAwait(false);
            var disconnectedPlayer = FindPlayer(hostRoom.CurrentSnapshot, ClientPeerId);
            Equal(originalSlot, disconnectedPlayer.Slot, "slot retained while client process is gone");
            True(!disconnectedPlayer.IsConnected, "host marks dead client offline");
            Equal(SolarRoomPhase.Playing, hostRoom.Phase, "host keeps active match alive");

            resumedTransport = hub.CreateEndpoint(ClientPeerId);
            resumedRoom = CreateRoom(HostPeerId, "MOON", resumedTransport);
            var resumedStartSignal = Signal<SolarGameStartInfo>();
            resumedRoom.GameStarted += info => resumedStartSignal.TrySetResult(info);
            resumedRoom.ProtocolFaulted += faults.Add;
            resumedRoom.Attach();
            await resumedTransport.StartAsync().ConfigureAwait(false);
            await resumedRoom.NotifyPeerConnectedAsync(HostPeerId).ConfigureAwait(false);

            var resumedStart = await WaitAsync(resumedStartSignal.Task, "resumed client game start").ConfigureAwait(false);
            Equal(GameSessionId, resumedStart.GameSessionId, "rejoined room preserves game session id");
            Equal(SolarRoomPhase.Playing, resumedRoom.Phase, "fresh client process rejoins playing room");
            var rejoinedPlayer = FindPlayer(hostRoom.CurrentSnapshot, ClientPeerId);
            Equal(originalSlot, rejoinedPlayer.Slot, "fresh process reclaims original slot");
            True(rejoinedPlayer.IsConnected, "fresh process is online in existing slot");
            Equal(2, hostRoom.CurrentSnapshot.Players.Length, "process resume never duplicates roster identity");

            var resumedState = new GridDuelStateMachine(resumedStart.PlayerIds[0], resumedStart.PlayerIds[1]);
            var snapshotSignal = Signal<SolarStateSnapshot>();
            var resyncFailures = new List<SolarResyncFailure>();
            resumedGame = new SolarTurnSession(
                resumedStart.GameSessionId,
                resumedStart.HostPeerId,
                resumedTransport,
                null,
                resumedState);
            resumedGame.SnapshotApplied += snapshot => snapshotSignal.TrySetResult(snapshot);
            resumedGame.ResyncFailed += resyncFailures.Add;
            resumedGame.ProtocolFaulted += faults.Add;
            await resumedGame.StartAsync().ConfigureAwait(false);

            await resumedGame.RequestResyncAsync(0).ConfigureAwait(false);
            var snapshot = await WaitAsync(snapshotSignal.Task, "authoritative snapshot after process restart").ConfigureAwait(false);
            Equal(3L, snapshot.NextTurnIndex, "snapshot restores current authoritative turn");
            Equal(3L, resumedGame.KnownNextTurnIndex, "fresh game session catches up to turn three");
            Equal(ClientPeerId, resumedGame.KnownCurrentPlayerId, "fresh game session restores active player");
            AssertConverged(hostState, resumedState, "after fresh-process snapshot recovery");
            Equal(hostGame.LastStateHash, resumedGame.LastStateHash, "fresh-process hash convergence");
            Equal(0, resyncFailures.Count, "process resume resync failures");

            await Attack(resumedGame).ConfigureAwait(false);
            AssertConverged(hostState, resumedState, "client continues immediately after process resume");
            Equal(2, hostState.GetPlayer(HostPeerId).Health, "resumed client attack reaches authoritative host");
            Equal(4L, hostGame.KnownNextTurnIndex, "turn advances after resumed client action");

            await Attack(hostGame).ConfigureAwait(false);
            AssertConverged(hostState, resumedState, "host continues after resumed client action");
            Equal(2, hostState.GetPlayer(ClientPeerId).Health, "host attack reaches resumed client");
            Equal(5L, resumedGame.KnownNextTurnIndex, "fresh client remains synchronized after continued play");
            Equal(0, faults.Count, "room plus game protocol faults during process resume");
        }
        finally
        {
            if (resumedRoom != null) resumedRoom.Detach();
            if (resumedGame != null) await resumedGame.StopAsync().ConfigureAwait(false);
            else if (resumedTransport != null) await resumedTransport.StopAsync().ConfigureAwait(false);

            initialClientRoom.Detach();
            if (initialClientGame != null) await initialClientGame.StopAsync().ConfigureAwait(false);
            hostRoom.Detach();
            if (hostGame != null) await hostGame.StopAsync().ConfigureAwait(false);
            else await hostTransport.StopAsync().ConfigureAwait(false);
        }
    }

    private static SolarRoomSession CreateRoom(string hostPeerId, string displayName, ISolarTransport transport)
    {
        return new SolarRoomSession(
            new SolarRoomOptions(
                RoomId,
                hostPeerId,
                displayName,
                "grid-duel-v1",
                "Grid Duel",
                2,
                SolarHostDisconnectPolicy.WaitForReconnect),
            transport);
    }

    private static SolarRoomPlayer FindPlayer(SolarRoomSnapshot snapshot, string peerId)
    {
        if (snapshot != null)
        {
            foreach (var player in snapshot.Players)
                if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player;
        }
        throw new Exception("Player not found in room snapshot: " + peerId);
    }

    private static Task Move(SolarTurnSession session, int x, int y)
    {
        return session.SubmitActionAsync(GridDuelActionCodec.MoveAction, GridDuelActionCodec.EncodeMove(x, y));
    }

    private static Task Attack(SolarTurnSession session)
    {
        return session.SubmitActionAsync(GridDuelActionCodec.AttackAction, Array.Empty<byte>());
    }

    private static void AssertConverged(GridDuelStateMachine expected, GridDuelStateMachine actual, string label)
    {
        var a = expected.CaptureSnapshot();
        var b = actual.CaptureSnapshot();
        Equal(a.Length, b.Length, label + " snapshot length");
        for (var i = 0; i < a.Length; i++)
            if (a[i] != b[i]) throw new Exception(label + " differs at byte " + i + ".");
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
