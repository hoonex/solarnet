using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.BluetoothClassic;
using SolarNet.Nearby;
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
            SynchronizedLinkLossMigratesGridDuelAuthority,
            DivergedStateBlocksMigrationPreparation,
            MigrationSwitchConfiguresBeforeStartAndConnect,
            BluetoothPeerAddressSurvivesDisconnect,
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

        Console.WriteLine("Grid Duel migration smoke tests passed: " + passed + "/" + tests.Count);
        return 0;
    }

    private static async Task SynchronizedLinkLossMigratesGridDuelAuthority()
    {
        var fixture = await CreateStartedRoomGameAsync("grid-migration-room", "grid-migration-game").ConfigureAwait(false);
        SolarRoomMigrationBootstrap formerHostRoom = null;
        SolarRoomMigrationBootstrap successorRoom = null;
        SolarTurnSession formerHostGame = null;
        SolarTurnSession successorGame = null;
        try
        {
            await Move(fixture.HostGame, 1, 2).ConfigureAwait(false);
            await Move(fixture.ClientGame, 3, 2).ConfigureAwait(false);
            AssertConverged(fixture.HostState, fixture.ClientState, "before migration");
            Equal(2L, fixture.HostGame.KnownNextTurnIndex, "source host next turn");
            Equal(2L, fixture.ClientGame.KnownNextTurnIndex, "source client next turn");

            var hostMigration = GridDuelMigrationWorkflow.Prepare(
                fixture.HostRoom.CurrentSnapshot,
                fixture.HostGame,
                fixture.HostState);
            var clientMigration = GridDuelMigrationWorkflow.Prepare(
                fixture.ClientRoom.CurrentSnapshot,
                fixture.ClientGame,
                fixture.ClientState);

            Equal("client", hostMigration.Plan.SuccessorPeerId, "stable slot-one successor");
            Equal(hostMigration.Plan.NextGameSessionId, clientMigration.Plan.NextGameSessionId, "shared migration epoch");
            Equal(hostMigration.Checkpoint.StateHash, clientMigration.Checkpoint.StateHash, "shared migration digest");
            Equal(2L, hostMigration.Checkpoint.NextTurnIndex, "checkpoint next turn");

            fixture.HostRoom.Detach();
            fixture.ClientRoom.Detach();
            await fixture.ClientGame.StopAsync().ConfigureAwait(false);
            await fixture.HostGame.StopAsync().ConfigureAwait(false);

            var migratedHub = new LoopbackTransportHub();
            var formerHostTransport = migratedHub.CreateEndpoint("host");
            var successorTransport = migratedHub.CreateEndpoint("client");

            formerHostRoom = GridDuelMigrationWorkflow.CreateRoom(hostMigration, formerHostTransport);
            successorRoom = GridDuelMigrationWorkflow.CreateRoom(clientMigration, successorTransport);
            formerHostRoom.RoomSession.Attach();
            successorRoom.RoomSession.Attach();

            formerHostGame = GridDuelMigrationWorkflow.CreateGame(
                hostMigration,
                formerHostRoom,
                formerHostTransport,
                fixture.HostState);
            successorGame = GridDuelMigrationWorkflow.CreateGame(
                clientMigration,
                successorRoom,
                successorTransport,
                fixture.ClientState);

            var faults = new List<Exception>();
            var snapshots = 0;
            formerHostRoom.RoomSession.ProtocolFaulted += faults.Add;
            successorRoom.RoomSession.ProtocolFaulted += faults.Add;
            formerHostGame.ProtocolFaulted += faults.Add;
            successorGame.ProtocolFaulted += faults.Add;
            formerHostGame.SnapshotApplied += _ => snapshots++;

            await successorGame.StartAsync().ConfigureAwait(false);
            await formerHostGame.StartAsync().ConfigureAwait(false);
            await formerHostRoom.RoomSession.NotifyPeerConnectedAsync("client").ConfigureAwait(false);

            Equal(SolarRoomPhase.Playing, formerHostRoom.RoomSession.Phase, "former host rejoins playing room");
            Equal("client", formerHostRoom.RoomSession.HostPeerId, "migrated room host identity");
            Equal(0, FindPlayer(successorRoom.RoomSession.CurrentSnapshot, "host").Slot, "former host keeps slot zero");
            Equal(1, FindPlayer(successorRoom.RoomSession.CurrentSnapshot, "client").Slot, "successor keeps slot one");
            True(successorGame.IsHost, "successor owns promoted game authority");
            True(successorGame.DurabilityBarrierEnabled, "promoted successor keeps a durability barrier");
            Equal("host", successorGame.RequiredReplicationPeerId, "former host becomes the promoted authority replica");
            True(!formerHostGame.IsHost, "former host is fenced to client role");

            await formerHostGame.RequestResyncAsync(0).ConfigureAwait(false);
            Equal(1, snapshots, "former host receives promoted snapshot");
            Equal(2L, formerHostGame.KnownNextTurnIndex, "snapshot preserves canonical turn");
            Equal("host", formerHostGame.KnownCurrentPlayerId, "source current player survives promotion");
            AssertConverged(fixture.ClientState, fixture.HostState, "after promoted snapshot");

            await Move(formerHostGame, 2, 2).ConfigureAwait(false);
            await Attack(successorGame).ConfigureAwait(false);
            Equal(4L, successorGame.KnownNextTurnIndex, "migrated match continues turns");
            Equal(2, fixture.HostState.GetPlayer("host").Health, "successor attack after migration");
            AssertConverged(fixture.ClientState, fixture.HostState, "after migrated turns");
            Equal(0, faults.Count, "migration protocol faults");
        }
        finally
        {
            if (formerHostRoom != null) formerHostRoom.RoomSession.Detach();
            if (successorRoom != null) successorRoom.RoomSession.Detach();
            if (formerHostGame != null) await formerHostGame.StopAsync().ConfigureAwait(false);
            if (successorGame != null) await successorGame.StopAsync().ConfigureAwait(false);
            await fixture.DisposeAsync().ConfigureAwait(false);
        }
    }

    private static async Task DivergedStateBlocksMigrationPreparation()
    {
        var fixture = await CreateStartedRoomGameAsync("grid-migration-safety-room", "grid-migration-safety-game").ConfigureAwait(false);
        try
        {
            await Move(fixture.HostGame, 1, 2).ConfigureAwait(false);
            True(
                fixture.ClientState.TryApply(new SolarGameAction(
                    "client",
                    fixture.ClientGame.KnownNextTurnIndex,
                    GridDuelActionCodec.MoveAction,
                    GridDuelActionCodec.EncodeMove(3, 2))),
                "test must diverge only the client reducer state");

            Throws<InvalidOperationException>(
                () => GridDuelMigrationWorkflow.Prepare(
                    fixture.ClientRoom.CurrentSnapshot,
                    fixture.ClientGame,
                    fixture.ClientState),
                "diverged local state must not produce a migration checkpoint");
        }
        finally
        {
            await fixture.DisposeAsync().ConfigureAwait(false);
        }
    }

    private static async Task MigrationSwitchConfiguresBeforeStartAndConnect()
    {
        var source = CreateThreePlayerMigrationSource();
        var plan = SolarHostMigrationPlanner.Create(source, CreateThreePlayerCheckpoint());

        var nearbyAdapter = new ProbeNearbyAdapter();
        var oldNearby = new NearbyTransport(
            "peer-c",
            nearbyAdapter,
            new NearbyTransportOptions("svc", "C", NearbyConnectionRole.Discoverer, NearbyConnectionStrategy.Star, false));
        await oldNearby.StartAsync().ConfigureAwait(false);
        nearbyAdapter.RequireConfigured = true;
        var nearbyConfigured = false;
        var discovered = 0;
        var nearby = await NearbyMigrationTransportSwitch.SwitchAsync(
            oldNearby,
            nearbyAdapter,
            source,
            plan,
            "svc",
            configureTransport: transport =>
            {
                nearbyConfigured = true;
                nearbyAdapter.ConfiguredProbe = () => nearbyConfigured;
                transport.EndpointDiscovered += _ => discovered++;
            }).ConfigureAwait(false);
        True(nearbyConfigured, "Nearby switch invokes pre-start configuration");
        Equal(1, discovered, "Nearby discovery emitted during Start is observable");
        await nearby.Transport.StopAsync().ConfigureAwait(false);

        var bluetoothServerAdapter = new ProbeBluetoothAdapter();
        var oldSuccessorBluetooth = new BluetoothClassicTransport(
            "peer-b",
            bluetoothServerAdapter,
            new BluetoothClassicTransportOptions(BluetoothClassicRole.Client));
        await oldSuccessorBluetooth.StartAsync().ConfigureAwait(false);
        bluetoothServerAdapter.RequireConfigured = true;
        var serverConfigured = false;
        var successor = await BluetoothClassicMigrationTransportSwitch.SwitchAsync(
            oldSuccessorBluetooth,
            bluetoothServerAdapter,
            source,
            plan,
            configureTransport: _ =>
            {
                serverConfigured = true;
                bluetoothServerAdapter.ConfiguredProbe = () => serverConfigured;
            }).ConfigureAwait(false);
        Equal(BluetoothClassicRole.Server, successor.Role, "successor Bluetooth role");
        Equal(1, bluetoothServerAdapter.StartServerCount, "configured successor server starts once");
        await successor.Transport.StopAsync().ConfigureAwait(false);

        var bluetoothClientAdapter = new ProbeBluetoothAdapter();
        var oldFormerHostBluetooth = new BluetoothClassicTransport(
            "host-a",
            bluetoothClientAdapter,
            new BluetoothClassicTransportOptions(BluetoothClassicRole.Server));
        await oldFormerHostBluetooth.StartAsync().ConfigureAwait(false);
        bluetoothClientAdapter.RequireConfigured = true;
        var clientConfigured = false;
        var formerHost = await BluetoothClassicMigrationTransportSwitch.SwitchAsync(
            oldFormerHostBluetooth,
            bluetoothClientAdapter,
            source,
            plan,
            "AA:BB:CC:DD:EE:FF",
            configureTransport: _ =>
            {
                clientConfigured = true;
                bluetoothClientAdapter.ConfiguredProbe = () => clientConfigured;
            }).ConfigureAwait(false);
        Equal(BluetoothClassicRole.Client, formerHost.Role, "former host Bluetooth role");
        Equal(1, bluetoothClientAdapter.ConnectCount, "configured client connects once");
        Equal("AA:BB:CC:DD:EE:FF", bluetoothClientAdapter.LastConnectAddress, "successor Bluetooth address");
        await formerHost.Transport.StopAsync().ConfigureAwait(false);
    }

    private static async Task BluetoothPeerAddressSurvivesDisconnect()
    {
        var hub = new FakeBluetoothHub();
        var hostAdapter = hub.Create("AA:00:00:00:00:11", "Host Phone");
        var clientAdapter = hub.Create("AA:00:00:00:00:22", "Client Phone");
        var hostTransport = new BluetoothClassicTransport(
            "host",
            hostAdapter,
            new BluetoothClassicTransportOptions(BluetoothClassicRole.Server));
        var clientTransport = new BluetoothClassicTransport(
            "client",
            clientAdapter,
            new BluetoothClassicTransportOptions(BluetoothClassicRole.Client));
        var hostPeer = Signal<string>();
        var clientPeer = Signal<string>();
        hostTransport.PeerConnected += peer => hostPeer.TrySetResult(peer);
        clientTransport.PeerConnected += peer => clientPeer.TrySetResult(peer);

        await hostTransport.StartAsync().ConfigureAwait(false);
        await clientTransport.StartAsync().ConfigureAwait(false);
        try
        {
            await clientTransport.ConnectAsync(hostAdapter.Address).ConfigureAwait(false);
            Equal("client", await WaitAsync(hostPeer.Task, "host Bluetooth peer").ConfigureAwait(false), "host sees client peer");
            Equal("host", await WaitAsync(clientPeer.Task, "client Bluetooth peer").ConfigureAwait(false), "client sees host peer");

            await clientTransport.DisconnectPeerAsync("host").ConfigureAwait(false);

            string address;
            True(clientTransport.TryGetLastKnownDeviceAddress("host", out address), "client retains authenticated host device address");
            Equal(hostAdapter.Address, address, "cached host Bluetooth address");
            True(hostTransport.TryGetLastKnownDeviceAddress("client", out address), "host retains authenticated client device address");
            Equal(clientAdapter.Address, address, "cached client Bluetooth address");
        }
        finally
        {
            await clientTransport.StopAsync().ConfigureAwait(false);
            await hostTransport.StopAsync().ConfigureAwait(false);
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

        fixture.HostRoom = new SolarRoomSession(
            new SolarRoomOptions(roomId, "host", "SUN", "grid-duel-v1", "Grid Duel", 2, SolarHostDisconnectPolicy.WaitForReconnect),
            fixture.HostTransport);
        fixture.ClientRoom = new SolarRoomSession(
            new SolarRoomOptions(roomId, "host", "MOON", "grid-duel-v1", "Grid Duel", 2, SolarHostDisconnectPolicy.WaitForReconnect),
            fixture.ClientTransport);
        var clientStartSignal = Signal<SolarGameStartInfo>();
        fixture.HostRoom.ProtocolFaulted += fixture.Faults.Add;
        fixture.ClientRoom.ProtocolFaulted += fixture.Faults.Add;
        fixture.ClientRoom.GameStarted += info => clientStartSignal.TrySetResult(info);
        fixture.HostRoom.Attach();
        fixture.ClientRoom.Attach();

        await fixture.ClientRoom.NotifyPeerConnectedAsync("host").ConfigureAwait(false);
        await fixture.HostRoom.SetReadyAsync(true).ConfigureAwait(false);
        await fixture.ClientRoom.SetReadyAsync(true).ConfigureAwait(false);
        True(fixture.HostRoom.CanStart, "host can start migration fixture");

        var hostStart = await fixture.HostRoom.StartGameAsync(gameSessionId).ConfigureAwait(false);
        var clientStart = await WaitAsync(clientStartSignal.Task, "Grid Duel client start").ConfigureAwait(false);
        fixture.HostState = new GridDuelStateMachine(hostStart.PlayerIds[0], hostStart.PlayerIds[1]);
        fixture.ClientState = new GridDuelStateMachine(clientStart.PlayerIds[0], clientStart.PlayerIds[1]);
        fixture.HostGame = new SolarTurnSession(
            hostStart.GameSessionId,
            hostStart.HostPeerId,
            fixture.HostTransport,
            hostStart.CreateHostTurnCoordinator(),
            fixture.HostState,
            256,
            "client");
        fixture.ClientGame = new SolarTurnSession(
            clientStart.GameSessionId,
            clientStart.HostPeerId,
            fixture.ClientTransport,
            null,
            fixture.ClientState);
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

    private static SolarRoomSnapshot CreateThreePlayerMigrationSource()
    {
        return new SolarRoomSnapshot(
            10,
            "room-m17",
            "Migration Room",
            "host-a",
            "migration-v1",
            3,
            SolarRoomPhase.Playing,
            "game-epoch-1",
            new[]
            {
                new SolarRoomPlayer(0, "host-a", "A", true, true),
                new SolarRoomPlayer(1, "peer-b", "B", true, true),
                new SolarRoomPlayer(2, "peer-c", "C", true, true),
            });
    }

    private static SolarAuthorityCheckpoint CreateThreePlayerCheckpoint()
    {
        var state = new byte[] { 0x2a };
        return new SolarAuthorityCheckpoint(
            "game-epoch-1",
            new[] { "host-a", "peer-b", "peer-c" },
            0,
            "host-a",
            1,
            SolarStateDigest.Compute(state),
            state);
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
        foreach (var player in snapshot.Players)
            if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player;
        throw new Exception("Room player not found: " + peerId);
    }

    private static void AssertConverged(GridDuelStateMachine left, GridDuelStateMachine right, string label)
    {
        var a = left.CaptureSnapshot();
        var b = right.CaptureSnapshot();
        Equal(a.Length, b.Length, label + " length");
        for (var i = 0; i < a.Length; i++)
            if (a[i] != b[i]) throw new Exception(label + " differs at byte " + i + ".");
    }

    private static TaskCompletionSource<T> Signal<T>()
    {
        return new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private static async Task<T> WaitAsync<T>(Task<T> task, string label)
    {
        var completed = await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(3))).ConfigureAwait(false);
        if (!ReferenceEquals(completed, task)) throw new TimeoutException("Timed out waiting for " + label + ".");
        return await task.ConfigureAwait(false);
    }

    private static void Throws<TException>(Action action, string label) where TException : Exception
    {
        try
        {
            action();
        }
        catch (TException)
        {
            return;
        }
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

    private sealed class ProbeNearbyAdapter : INearbyPeerAdapter
    {
        public event Action<NearbyEndpoint> EndpointFound;
        public event Action<string> EndpointLost;
        public event Action<NearbyVerificationRequest> VerificationRequired;
        public event Action<string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public bool RequireConfigured;
        public Func<bool> ConfiguredProbe;
        public int StopAllCount;

        public Task StartAdvertisingAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureConfigured();
            return Task.CompletedTask;
        }

        public Task StartDiscoveryAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureConfigured();
            var found = EndpointFound;
            if (RequireConfigured && found != null)
                found(new NearbyEndpoint("successor-endpoint", NearbyRoomAdvertisementCodec.Encode("room-m17", "Migration Room", "migration-v1")));
            return Task.CompletedTask;
        }

        public Task StopAdvertisingAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task StopDiscoveryAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task RequestConnectionAsync(string endpointId, string endpointName, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task AcceptConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task RejectConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task DisconnectAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task SendBytesAsync(string endpointId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken)) { StopAllCount++; return Task.CompletedTask; }

        private void EnsureConfigured()
        {
            if (RequireConfigured && (ConfiguredProbe == null || !ConfiguredProbe()))
                throw new InvalidOperationException("Nearby transport started before caller configuration.");
        }
    }

    private sealed class ProbeBluetoothAdapter : IBluetoothClassicPeerAdapter
    {
        public event Action<string, string, string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public bool RequireConfigured;
        public Func<bool> ConfiguredProbe;
        public int StartServerCount;
        public int ConnectCount;
        public string LastConnectAddress;

        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices() { return Array.Empty<BluetoothClassicDevice>(); }
        public Task StartServerAsync(string serviceName, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureConfigured();
            StartServerCount++;
            return Task.CompletedTask;
        }
        public Task ConnectAsync(string deviceAddress, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureConfigured();
            ConnectCount++;
            LastConnectAddress = deviceAddress;
            return Task.CompletedTask;
        }
        public Task SendBytesAsync(string connectionId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task DisconnectAsync(string connectionId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }

        private void EnsureConfigured()
        {
            if (RequireConfigured && (ConfiguredProbe == null || !ConfiguredProbe()))
                throw new InvalidOperationException("Bluetooth transport started or connected before caller configuration.");
        }
    }

    private sealed class FakeBluetoothHub
    {
        private readonly Dictionary<string, FakeBluetoothAdapter> _adapters = new Dictionary<string, FakeBluetoothAdapter>(StringComparer.OrdinalIgnoreCase);
        private FakeBluetoothAdapter _server;
        private int _nextId;

        public FakeBluetoothAdapter Create(string address, string name)
        {
            var adapter = new FakeBluetoothAdapter(this, address, name);
            _adapters.Add(address, adapter);
            return adapter;
        }

        public void StartServer(FakeBluetoothAdapter adapter) { _server = adapter; }

        public IReadOnlyList<BluetoothClassicDevice> BondedFor(FakeBluetoothAdapter adapter)
        {
            var list = new List<BluetoothClassicDevice>();
            foreach (var pair in _adapters)
                if (!ReferenceEquals(pair.Value, adapter)) list.Add(new BluetoothClassicDevice(pair.Value.Address, pair.Value.Name));
            return list;
        }

        public void Connect(FakeBluetoothAdapter client, string address)
        {
            FakeBluetoothAdapter host;
            if (!_adapters.TryGetValue(address, out host) || !ReferenceEquals(host, _server))
                throw new InvalidOperationException("Fake Bluetooth server unavailable.");
            var id = "link-" + (++_nextId);
            client.Bind(id, host);
            host.Bind(id, client);
            host.RaiseConnected(id, client.Address, client.Name);
            client.RaiseConnected(id, host.Address, host.Name);
        }

        public Task Route(FakeBluetoothAdapter sender, string id, byte[] bytes)
        {
            var target = sender.GetRemote(id);
            target.RaiseBytes(id, Clone(bytes));
            return Task.CompletedTask;
        }

        public Task Disconnect(FakeBluetoothAdapter sender, string id)
        {
            var target = sender.GetRemote(id);
            sender.Unbind(id);
            target.Unbind(id);
            sender.RaiseDisconnected(id);
            target.RaiseDisconnected(id);
            return Task.CompletedTask;
        }

        private static byte[] Clone(byte[] value)
        {
            var copy = new byte[value.Length];
            Buffer.BlockCopy(value, 0, copy, 0, value.Length);
            return copy;
        }
    }

    private sealed class FakeBluetoothAdapter : IBluetoothClassicPeerAdapter
    {
        private readonly FakeBluetoothHub _hub;
        private readonly Dictionary<string, FakeBluetoothAdapter> _links = new Dictionary<string, FakeBluetoothAdapter>(StringComparer.Ordinal);

        public FakeBluetoothAdapter(FakeBluetoothHub hub, string address, string name)
        {
            _hub = hub;
            Address = address;
            Name = name;
        }

        public string Address { get; private set; }
        public string Name { get; private set; }
        public event Action<string, string, string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices() { return _hub.BondedFor(this); }
        public Task StartServerAsync(string serviceName, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken)) { _hub.StartServer(this); return Task.CompletedTask; }
        public Task ConnectAsync(string deviceAddress, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken)) { _hub.Connect(this, deviceAddress); return Task.CompletedTask; }
        public Task SendBytesAsync(string connectionId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return _hub.Route(this, connectionId, payload); }
        public async Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            foreach (var id in new List<string>(_links.Keys))
                await _hub.Route(this, id, payload).ConfigureAwait(false);
        }
        public Task DisconnectAsync(string connectionId, CancellationToken cancellationToken = default(CancellationToken)) { return _hub.Disconnect(this, connectionId); }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken)) { _links.Clear(); return Task.CompletedTask; }
        public void Bind(string id, FakeBluetoothAdapter remote) { _links[id] = remote; }
        public void Unbind(string id) { _links.Remove(id); }
        public FakeBluetoothAdapter GetRemote(string id) { return _links[id]; }
        public void RaiseConnected(string id, string address, string name) { var handler = Connected; if (handler != null) handler(id, address, name); }
        public void RaiseDisconnected(string id) { var handler = Disconnected; if (handler != null) handler(id); }
        public void RaiseBytes(string id, byte[] bytes) { var handler = BytesReceived; if (handler != null) handler(id, bytes); }
    }
}
