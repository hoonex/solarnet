using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.BluetoothClassic;
using SolarNet.Nearby;
using SolarNet.Room;
using SolarNet.Session;
using SolarNet.State;
using SolarNet.Transport;

internal static class Program
{
    private static async Task<int> Main()
    {
        try
        {
            await MigratedRoomPreservesSlotsAndContinuesGame();
            await NearbyRoleSwitchRebuildsTopology();
            await BluetoothRoleSwitchModelsAddressRequirement();
            Console.WriteLine("SolarNet migration orchestration smoke tests passed.");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex);
            return 1;
        }
    }

    private static async Task MigratedRoomPreservesSlotsAndContinuesGame()
    {
        var source = CreateSourceRoom();
        var checkpoint = CreateCheckpoint();
        var plan = SolarHostMigrationPlanner.Create(source, checkpoint);
        Equal("peer-b", plan.SuccessorPeerId, "slot-1 peer must be elected successor");

        var hub = new LoopbackTransportHub();
        var successorTransport = hub.CreateEndpoint("peer-b");
        var survivorTransport = hub.CreateEndpoint("peer-c");
        await successorTransport.StartAsync();
        await survivorTransport.StartAsync();

        var successorBootstrap = SolarRoomMigration.CreateSession(source, plan, successorTransport);
        var survivorBootstrap = SolarRoomMigration.CreateSession(source, plan, survivorTransport);
        True(successorBootstrap.IsSuccessor, "successor bootstrap role");
        True(!survivorBootstrap.IsSuccessor, "survivor bootstrap role");

        var roomFaults = 0;
        successorBootstrap.RoomSession.ProtocolFaulted += _ => roomFaults++;
        survivorBootstrap.RoomSession.ProtocolFaulted += _ => roomFaults++;
        SolarGameStartInfo migratedStart = null;
        survivorBootstrap.RoomSession.GameStarted += start => migratedStart = start;
        successorBootstrap.RoomSession.Attach();
        survivorBootstrap.RoomSession.Attach();

        var seeded = successorBootstrap.RoomSession.CurrentSnapshot;
        Equal(SolarRoomPhase.Playing, seeded.Phase, "migrated host room phase");
        Equal("peer-b", seeded.HostPeerId, "migrated host identity");
        Equal(plan.NextGameSessionId, seeded.GameSessionId, "migrated room game epoch");
        Equal(0, FindPlayer(seeded, "host-a").Slot, "former host slot must be preserved");
        Equal(1, FindPlayer(seeded, "peer-b").Slot, "successor slot must be preserved");
        True(!FindPlayer(seeded, "host-a").IsConnected, "dead former host must start offline");
        True(FindPlayer(seeded, "peer-b").IsConnected, "successor must start online");

        await survivorBootstrap.RoomSession.NotifyPeerConnectedAsync("peer-b");
        Equal(0, roomFaults, "migrated room protocol faults");
        True(migratedStart != null, "survivor must receive migrated game start");
        Equal(plan.NextGameSessionId, migratedStart.GameSessionId, "survivor migrated game epoch");
        Equal("peer-b", migratedStart.HostPeerId, "survivor migrated authority");
        Equal(1, FindPlayer(survivorBootstrap.RoomSession.CurrentSnapshot, "peer-b").Slot, "client must accept host outside slot zero");

        var successorState = new CounterStateMachine(0);
        var survivorState = new CounterStateMachine(0);
        var hostGame = SolarAuthorityPromotion.CreatePromotedHost(
            plan.NextGameSessionId,
            plan.SuccessorPeerId,
            successorTransport,
            checkpoint,
            successorState);
        var clientGame = new SolarTurnSession(
            plan.NextGameSessionId,
            plan.SuccessorPeerId,
            survivorTransport,
            null,
            survivorState);

        var gameFaults = 0;
        var snapshots = 0;
        hostGame.ProtocolFaulted += _ => gameFaults++;
        clientGame.ProtocolFaulted += _ => gameFaults++;
        clientGame.SnapshotApplied += _ => snapshots++;
        await hostGame.StartAsync();
        await clientGame.StartAsync();
        await clientGame.RequestResyncAsync(0);

        Equal(1, snapshots, "survivor must recover promoted authority snapshot");
        Equal(4, survivorState.Value, "survivor state after promoted-host resync");
        Equal(4L, clientGame.KnownNextTurnIndex, "survivor turn after promoted-host resync");
        Equal("peer-b", clientGame.KnownCurrentPlayerId, "successor must retain active turn");

        await hostGame.SubmitActionAsync("add", EncodeInt(2));
        Equal(6, successorState.Value, "successor state after turn 4");
        Equal(6, survivorState.Value, "survivor state after turn 4");
        Equal(5L, clientGame.KnownNextTurnIndex, "next turn after successor action");
        Equal("peer-c", clientGame.KnownCurrentPlayerId, "survivor must become active player");

        await clientGame.SubmitActionAsync("add", EncodeInt(3));
        Equal(9, successorState.Value, "successor state after survivor action");
        Equal(9, survivorState.Value, "survivor state after its action");
        Equal(6L, hostGame.KnownNextTurnIndex, "promoted authority must continue canonical turn sequence");
        Equal(0, gameFaults, "migrated game protocol faults");

        successorBootstrap.RoomSession.Detach();
        survivorBootstrap.RoomSession.Detach();
        await hostGame.StopAsync();
        await clientGame.StopAsync();
    }

    private static async Task NearbyRoleSwitchRebuildsTopology()
    {
        var source = CreateSourceRoom();
        var plan = SolarHostMigrationPlanner.Create(source, CreateCheckpoint());

        var successorAdapter = new FakeNearbyAdapter();
        var oldSuccessor = new NearbyTransport(
            "peer-b",
            successorAdapter,
            new NearbyTransportOptions("svc", "B", NearbyConnectionRole.Discoverer, NearbyConnectionStrategy.Star, false));
        await oldSuccessor.StartAsync();
        var successor = await NearbyMigrationTransportSwitch.SwitchAsync(oldSuccessor, successorAdapter, source, plan, "svc");
        Equal(NearbyConnectionRole.Advertiser, successor.Role, "Nearby successor role");
        Equal(1, successorAdapter.StopAllCount, "Nearby old topology must stop before role switch");
        Equal(1, successorAdapter.StartAdvertisingCount, "Nearby successor must republish as advertiser");
        SolarNearbyRoomAdvertisement advertisement;
        True(NearbyRoomAdvertisementCodec.TryDecode(successorAdapter.LastAdvertisingName, out advertisement), "migrated Nearby advert must be decodable");
        Equal(source.RoomId, advertisement.RoomId, "migrated Nearby advert room ID");

        var survivorAdapter = new FakeNearbyAdapter();
        var oldSurvivor = new NearbyTransport(
            "peer-c",
            survivorAdapter,
            new NearbyTransportOptions("svc", "C", NearbyConnectionRole.Discoverer, NearbyConnectionStrategy.Star, false));
        await oldSurvivor.StartAsync();
        var survivor = await NearbyMigrationTransportSwitch.SwitchAsync(oldSurvivor, survivorAdapter, source, plan, "svc");
        Equal(NearbyConnectionRole.Discoverer, survivor.Role, "Nearby survivor role");
        var endpoint = new NearbyEndpoint("endpoint-successor", successorAdapter.LastAdvertisingName);
        True(survivor.IsSuccessorAdvertisement(endpoint), "survivor must recognize migrated room advertisement");
        await survivor.RequestSuccessorConnectionAsync(endpoint);
        Equal("endpoint-successor", survivorAdapter.LastRequestedEndpoint, "survivor must request successor endpoint");
        True(survivor.IsExpectedAuthority("peer-b"), "logical peer handshake must match elected authority");
        True(!survivor.IsExpectedAuthority("host-a"), "former authority must not satisfy migrated authority fence");

        await successor.Transport.StopAsync();
        await survivor.Transport.StopAsync();
    }

    private static async Task BluetoothRoleSwitchModelsAddressRequirement()
    {
        var source = CreateSourceRoom();
        var plan = SolarHostMigrationPlanner.Create(source, CreateCheckpoint());

        var successorAdapter = new FakeBluetoothAdapter();
        var oldSuccessor = new BluetoothClassicTransport(
            "peer-b",
            successorAdapter,
            new BluetoothClassicTransportOptions(BluetoothClassicRole.Client));
        await oldSuccessor.StartAsync();
        var successor = await BluetoothClassicMigrationTransportSwitch.SwitchAsync(oldSuccessor, successorAdapter, source, plan);
        Equal(BluetoothClassicRole.Server, successor.Role, "Bluetooth successor role");
        Equal(1, successorAdapter.StartServerCount, "Bluetooth successor must start RFCOMM server");
        True(!successor.RequiresSuccessorDeviceAddress, "Bluetooth successor never needs its own address");

        var survivorAdapter = new FakeBluetoothAdapter();
        var oldSurvivor = new BluetoothClassicTransport(
            "peer-c",
            survivorAdapter,
            new BluetoothClassicTransportOptions(BluetoothClassicRole.Client));
        await oldSurvivor.StartAsync();
        var survivorWithoutAddress = await BluetoothClassicMigrationTransportSwitch.SwitchAsync(oldSurvivor, survivorAdapter, source, plan);
        Equal(BluetoothClassicRole.Client, survivorWithoutAddress.Role, "Bluetooth survivor role");
        True(survivorWithoutAddress.RequiresSuccessorDeviceAddress, "Bluetooth survivor must report missing successor device address");
        Equal(0, survivorAdapter.ConnectCount, "Bluetooth client must not guess a successor device address");
        await survivorWithoutAddress.Transport.StopAsync();

        var reconnectAdapter = new FakeBluetoothAdapter();
        var oldReconnect = new BluetoothClassicTransport(
            "peer-c",
            reconnectAdapter,
            new BluetoothClassicTransportOptions(BluetoothClassicRole.Client));
        await oldReconnect.StartAsync();
        var survivorWithAddress = await BluetoothClassicMigrationTransportSwitch.SwitchAsync(
            oldReconnect,
            reconnectAdapter,
            source,
            plan,
            "AA:BB:CC:DD:EE:FF");
        True(!survivorWithAddress.RequiresSuccessorDeviceAddress, "Bluetooth survivor with resolved address can reconnect immediately");
        Equal(1, reconnectAdapter.ConnectCount, "Bluetooth successor address must be used exactly once");
        Equal("AA:BB:CC:DD:EE:FF", reconnectAdapter.LastConnectAddress, "Bluetooth successor address");

        await successor.Transport.StopAsync();
        await survivorWithAddress.Transport.StopAsync();
    }

    private static SolarRoomSnapshot CreateSourceRoom()
    {
        return new SolarRoomSnapshot(
            10,
            "room-m16",
            "Migration Room",
            "host-a",
            "migration-v1",
            3,
            SolarRoomPhase.Playing,
            "game-epoch-1",
            new[]
            {
                new SolarRoomPlayer(0, "host-a", "A", true, false),
                new SolarRoomPlayer(1, "peer-b", "B", true, true),
                new SolarRoomPlayer(2, "peer-c", "C", true, true),
            });
    }

    private static SolarAuthorityCheckpoint CreateCheckpoint()
    {
        var state = EncodeInt(4);
        return new SolarAuthorityCheckpoint(
            "game-epoch-1",
            new[] { "host-a", "peer-b", "peer-c" },
            4,
            "peer-b",
            2,
            SolarStateDigest.Compute(state),
            state);
    }

    private static SolarRoomPlayer FindPlayer(SolarRoomSnapshot snapshot, string peerId)
    {
        foreach (var player in snapshot.Players)
            if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player;
        throw new Exception("Player not found: " + peerId);
    }

    private static byte[] EncodeInt(int value)
    {
        return new[]
        {
            (byte)((value >> 24) & 0xff),
            (byte)((value >> 16) & 0xff),
            (byte)((value >> 8) & 0xff),
            (byte)(value & 0xff),
        };
    }

    private static int DecodeInt(byte[] bytes)
    {
        if (bytes == null || bytes.Length != 4) throw new InvalidOperationException("Expected one canonical 32-bit integer.");
        return (bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3];
    }

    private static void True(bool condition, string message)
    {
        if (!condition) throw new Exception("Assertion failed: " + message);
    }

    private static void Equal<T>(T expected, T actual, string message)
    {
        if (!EqualityComparer<T>.Default.Equals(expected, actual))
            throw new Exception("Assertion failed: " + message + ". expected=" + expected + " actual=" + actual);
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

    private sealed class FakeNearbyAdapter : INearbyPeerAdapter
    {
        public event Action<NearbyEndpoint> EndpointFound;
        public event Action<string> EndpointLost;
        public event Action<NearbyVerificationRequest> VerificationRequired;
        public event Action<string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public int StartAdvertisingCount;
        public int StartDiscoveryCount;
        public int StopAllCount;
        public string LastAdvertisingName;
        public string LastRequestedEndpoint;

        public Task StartAdvertisingAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken))
        { cancellationToken.ThrowIfCancellationRequested(); StartAdvertisingCount++; LastAdvertisingName = endpointName; return Task.CompletedTask; }
        public Task StartDiscoveryAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken))
        { cancellationToken.ThrowIfCancellationRequested(); StartDiscoveryCount++; return Task.CompletedTask; }
        public Task StopAdvertisingAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task StopDiscoveryAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task RequestConnectionAsync(string endpointId, string endpointName, CancellationToken cancellationToken = default(CancellationToken))
        { cancellationToken.ThrowIfCancellationRequested(); LastRequestedEndpoint = endpointId; return Task.CompletedTask; }
        public Task AcceptConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task RejectConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task DisconnectAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task SendBytesAsync(string endpointId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken))
        { cancellationToken.ThrowIfCancellationRequested(); StopAllCount++; return Task.CompletedTask; }
    }

    private sealed class FakeBluetoothAdapter : IBluetoothClassicPeerAdapter
    {
        public event Action<string, string, string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public int StartServerCount;
        public int ConnectCount;
        public int StopAllCount;
        public string LastConnectAddress;

        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices() { return Array.Empty<BluetoothClassicDevice>(); }
        public Task StartServerAsync(string serviceName, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken))
        { cancellationToken.ThrowIfCancellationRequested(); StartServerCount++; return Task.CompletedTask; }
        public Task ConnectAsync(string deviceAddress, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken))
        { cancellationToken.ThrowIfCancellationRequested(); ConnectCount++; LastConnectAddress = deviceAddress; return Task.CompletedTask; }
        public Task SendBytesAsync(string connectionId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task DisconnectAsync(string connectionId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken))
        { cancellationToken.ThrowIfCancellationRequested(); StopAllCount++; return Task.CompletedTask; }
    }
}
