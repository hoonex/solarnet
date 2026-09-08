using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.BluetoothClassic;
using SolarNet.Session;
using SolarNet.Turns;

internal static class Program
{
    private static async Task<int> Main()
    {
        try
        {
            await BluetoothClassicCarriesAuthoritativeTurns().ConfigureAwait(false);
            Console.WriteLine("PASS BluetoothClassicCarriesAuthoritativeTurns");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("FAIL BluetoothClassicCarriesAuthoritativeTurns: " + ex);
            return 1;
        }
    }

    private static async Task BluetoothClassicCarriesAuthoritativeTurns()
    {
        var hub = new FakeBluetoothHub();
        var hostAdapter = hub.Create("AA:00:00:00:00:01", "Host Phone");
        var clientAdapter = hub.Create("AA:00:00:00:00:02", "Client Phone");
        var hostTransport = new BluetoothClassicTransport("host", hostAdapter, new BluetoothClassicTransportOptions(BluetoothClassicRole.Server));
        var clientTransport = new BluetoothClassicTransport("client", clientAdapter, new BluetoothClassicTransportOptions(BluetoothClassicRole.Client));
        var hostPeer = Signal<string>();
        var clientPeer = Signal<string>();
        var faults = new List<Exception>();
        hostTransport.PeerConnected += peer => { hostPeer.TrySetResult(peer); };
        clientTransport.PeerConnected += peer => { clientPeer.TrySetResult(peer); };
        hostTransport.Faulted += faults.Add;
        clientTransport.Faulted += faults.Add;

        await hostTransport.StartAsync().ConfigureAwait(false);
        await clientTransport.StartAsync().ConfigureAwait(false);
        try
        {
            var bonded = clientTransport.GetBondedDevices();
            Equal(1, bonded.Count, "client bonded device count");
            Equal(hostAdapter.Address, bonded[0].Address, "bonded host address");
            await clientTransport.ConnectAsync(hostAdapter.Address).ConfigureAwait(false);
            Equal("client", await WaitAsync(hostPeer.Task, "host peer handshake").ConfigureAwait(false), "host sees client peer id");
            Equal("host", await WaitAsync(clientPeer.Task, "client peer handshake").ConfigureAwait(false), "client sees host peer id");

            var coordinator = new TurnCoordinator(new[] { "host", "client" });
            var hostSession = new SolarTurnSession("bluetooth-room", "host", hostTransport, coordinator);
            var clientSession = new SolarTurnSession("bluetooth-room", "host", clientTransport);
            var hostCommits = 0;
            var clientCommits = 0;
            hostSession.ActionCommitted += _ => hostCommits++;
            clientSession.ActionCommitted += _ => clientCommits++;
            await hostSession.StartAsync().ConfigureAwait(false);
            await clientSession.StartAsync().ConfigureAwait(false);
            try
            {
                await hostSession.SubmitActionAsync(0, "move", new byte[] { 1 }).ConfigureAwait(false);
                await clientSession.SubmitActionAsync(1, "attack", new byte[] { 2 }).ConfigureAwait(false);
                Equal(2, hostCommits, "host authoritative commits");
                Equal(2, clientCommits, "client authoritative commits");
                Equal(0, faults.Count, "transport faults");
            }
            finally
            {
                await clientSession.StopAsync().ConfigureAwait(false);
                await hostSession.StopAsync().ConfigureAwait(false);
            }
        }
        finally
        {
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
        var timeout = Task.Delay(TimeSpan.FromSeconds(3));
        var completed = await Task.WhenAny(task, timeout).ConfigureAwait(false);
        if (!ReferenceEquals(completed, task)) throw new TimeoutException("Timed out waiting for " + label + ".");
        return await task.ConfigureAwait(false);
    }

    private static void Equal<T>(T expected, T actual, string label)
    {
        if (!EqualityComparer<T>.Default.Equals(expected, actual))
            throw new Exception(label + " expected <" + expected + "> but got <" + actual + ">.");
    }

    private sealed class FakeBluetoothHub
    {
        private readonly Dictionary<string, FakeAdapter> _adapters = new Dictionary<string, FakeAdapter>(StringComparer.OrdinalIgnoreCase);
        private FakeAdapter _server;
        private int _nextId;

        public FakeAdapter Create(string address, string name)
        {
            var adapter = new FakeAdapter(this, address, name);
            _adapters.Add(address, adapter);
            return adapter;
        }

        public void StartServer(FakeAdapter adapter) { _server = adapter; }

        public IReadOnlyList<BluetoothClassicDevice> BondedFor(FakeAdapter adapter)
        {
            var list = new List<BluetoothClassicDevice>();
            foreach (var pair in _adapters)
                if (!ReferenceEquals(pair.Value, adapter)) list.Add(new BluetoothClassicDevice(pair.Value.Address, pair.Value.Name));
            return list;
        }

        public void Connect(FakeAdapter client, string address)
        {
            FakeAdapter host;
            if (!_adapters.TryGetValue(address, out host) || !ReferenceEquals(host, _server)) throw new InvalidOperationException("Fake Bluetooth server unavailable.");
            var id = "link-" + (++_nextId);
            client.Bind(id, host);
            host.Bind(id, client);
            host.RaiseConnected(id, client.Address, client.Name);
            client.RaiseConnected(id, host.Address, host.Name);
        }

        public Task Route(FakeAdapter sender, string id, byte[] bytes)
        {
            var target = sender.GetRemote(id);
            target.RaiseBytes(id, Clone(bytes));
            return Task.CompletedTask;
        }

        public Task Disconnect(FakeAdapter sender, string id)
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

    private sealed class FakeAdapter : IBluetoothClassicPeerAdapter
    {
        private readonly FakeBluetoothHub _hub;
        private readonly Dictionary<string, FakeAdapter> _links = new Dictionary<string, FakeAdapter>(StringComparer.Ordinal);
        public FakeAdapter(FakeBluetoothHub hub, string address, string name) { _hub = hub; Address = address; Name = name; }
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
        public async Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { foreach (var id in new List<string>(_links.Keys)) await _hub.Route(this, id, payload).ConfigureAwait(false); }
        public Task DisconnectAsync(string connectionId, CancellationToken cancellationToken = default(CancellationToken)) { return _hub.Disconnect(this, connectionId); }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken)) { _links.Clear(); return Task.CompletedTask; }
        public void Bind(string id, FakeAdapter remote) { _links[id] = remote; }
        public void Unbind(string id) { _links.Remove(id); }
        public FakeAdapter GetRemote(string id) { return _links[id]; }
        public void RaiseConnected(string id, string address, string name) { var h = Connected; if (h != null) h(id, address, name); }
        public void RaiseDisconnected(string id) { var h = Disconnected; if (h != null) h(id); }
        public void RaiseBytes(string id, byte[] bytes) { var h = BytesReceived; if (h != null) h(id, bytes); }
    }
}
