using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Nearby;
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
            EndToEndHostClientTurns,
            NearbyTransportMapsEndpointIdsAndCarriesTurns
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

    private static async Task NearbyTransportMapsEndpointIdsAndCarriesTurns()
    {
        var hub = new FakeNearbyHub();
        var hostAdapter = hub.Create("nearby-endpoint-A");
        var clientAdapter = hub.Create("nearby-endpoint-B");
        var hostTransport = new NearbyTransport(
            "host-peer",
            hostAdapter,
            new NearbyTransportOptions("com.hoonex.solarnet.smoke", "Host Phone", NearbyConnectionRole.Advertiser));
        var clientTransport = new NearbyTransport(
            "client-peer",
            clientAdapter,
            new NearbyTransportOptions("com.hoonex.solarnet.smoke", "Client Phone", NearbyConnectionRole.Discoverer));

        var coordinator = new TurnCoordinator(new[] { "host-peer", "client-peer" });
        var host = new SolarTurnSession("nearby-room", "host-peer", hostTransport, coordinator);
        var client = new SolarTurnSession("nearby-room", "host-peer", clientTransport);
        var hostPeers = new List<string>();
        var clientPeers = new List<string>();
        var hostCommits = new List<SolarTurnCommit>();
        var clientCommits = new List<SolarTurnCommit>();
        var faults = new List<Exception>();

        hostTransport.PeerConnected += hostPeers.Add;
        clientTransport.PeerConnected += clientPeers.Add;
        hostTransport.Faulted += faults.Add;
        clientTransport.Faulted += faults.Add;
        host.ActionCommitted += hostCommits.Add;
        client.ActionCommitted += clientCommits.Add;
        host.ProtocolFaulted += faults.Add;
        client.ProtocolFaulted += faults.Add;

        await host.StartAsync().ConfigureAwait(false);
        await client.StartAsync().ConfigureAwait(false);
        try
        {
            await hub.ConnectAsync(hostAdapter, clientAdapter).ConfigureAwait(false);
            Equal(1, hostPeers.Count, "host peer handshake count");
            Equal("client-peer", hostPeers[0], "host maps remote Nearby endpoint to SolarNet peer ID");
            Equal(1, clientPeers.Count, "client peer handshake count");
            Equal("host-peer", clientPeers[0], "client maps remote Nearby endpoint to SolarNet peer ID");

            await host.SubmitActionAsync(0, "move", new byte[] { 1 }).ConfigureAwait(false);
            await client.SubmitActionAsync(1, "attack", new byte[] { 2 }).ConfigureAwait(false);

            Equal(2, hostCommits.Count, "Nearby host commit count");
            Equal(2, clientCommits.Count, "Nearby client commit count");
            Equal("client-peer", clientCommits[1].ActorId, "Nearby client action actor");
            Equal(0, faults.Count, "Nearby transport faults");
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

    private sealed class FakeNearbyHub
    {
        private readonly Dictionary<string, FakeNearbyAdapter> _adapters = new Dictionary<string, FakeNearbyAdapter>(StringComparer.Ordinal);

        public FakeNearbyAdapter Create(string endpointId)
        {
            var adapter = new FakeNearbyAdapter(this, endpointId);
            _adapters.Add(endpointId, adapter);
            return adapter;
        }

        public async Task ConnectAsync(FakeNearbyAdapter left, FakeNearbyAdapter right)
        {
            left.AddConnection(right.EndpointId);
            right.AddConnection(left.EndpointId);
            left.RaiseConnected(right.EndpointId);
            right.RaiseConnected(left.EndpointId);
            await Task.Yield();
        }

        public Task RouteAsync(string senderEndpointId, string targetEndpointId, byte[] payload)
        {
            FakeNearbyAdapter target;
            if (!_adapters.TryGetValue(targetEndpointId, out target)) throw new InvalidOperationException("Unknown fake Nearby endpoint: " + targetEndpointId);
            target.RaiseBytes(senderEndpointId, Clone(payload));
            return Task.CompletedTask;
        }

        private static byte[] Clone(byte[] source)
        {
            var copy = new byte[source.Length];
            Buffer.BlockCopy(source, 0, copy, 0, source.Length);
            return copy;
        }
    }

    private sealed class FakeNearbyAdapter : INearbyPeerAdapter
    {
        private readonly FakeNearbyHub _hub;
        private readonly HashSet<string> _connected = new HashSet<string>(StringComparer.Ordinal);

        public FakeNearbyAdapter(FakeNearbyHub hub, string endpointId)
        {
            _hub = hub;
            EndpointId = endpointId;
        }

        public string EndpointId { get; private set; }
        public event Action<NearbyEndpoint> EndpointFound;
        public event Action<string> EndpointLost;
        public event Action<NearbyVerificationRequest> VerificationRequired;
        public event Action<string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public Task StartAdvertisingAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken)) { cancellationToken.ThrowIfCancellationRequested(); return Task.CompletedTask; }
        public Task StartDiscoveryAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken)) { cancellationToken.ThrowIfCancellationRequested(); return Task.CompletedTask; }
        public Task StopAdvertisingAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task StopDiscoveryAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task RequestConnectionAsync(string endpointId, string endpointName, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task AcceptConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }
        public Task RejectConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Task.CompletedTask; }

        public Task DisconnectAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken))
        {
            _connected.Remove(endpointId);
            var handler = Disconnected;
            if (handler != null) handler(endpointId);
            return Task.CompletedTask;
        }

        public Task SendBytesAsync(string endpointId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!_connected.Contains(endpointId)) throw new InvalidOperationException("Fake Nearby endpoint is not connected: " + endpointId);
            return _hub.RouteAsync(EndpointId, endpointId, payload);
        }

        public async Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            foreach (var endpointId in new List<string>(_connected))
                await _hub.RouteAsync(EndpointId, endpointId, payload).ConfigureAwait(false);
        }

        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            _connected.Clear();
            return Task.CompletedTask;
        }

        public void AddConnection(string endpointId) { _connected.Add(endpointId); }
        public void RaiseConnected(string endpointId) { var handler = Connected; if (handler != null) handler(endpointId); }
        public void RaiseBytes(string endpointId, byte[] payload) { var handler = BytesReceived; if (handler != null) handler(endpointId, payload); }
    }
}
