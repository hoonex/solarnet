using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.BluetoothClassic;

#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine;
#endif

namespace SolarNet.BluetoothClassic.Android
{
#if UNITY_ANDROID && !UNITY_EDITOR
    public sealed class AndroidBluetoothClassicAdapter : IBluetoothClassicPeerAdapter, IDisposable
    {
        private const string BridgeClassName = "com.hoonex.solarnet.bluetoothclassic.SolarBluetoothClassicBridge";
        private readonly object _gate = new object();
        private readonly Dictionary<long, TaskCompletionSource<bool>> _operations = new Dictionary<long, TaskCompletionSource<bool>>();
        private readonly SynchronizationContext _unityContext;
        private readonly CallbackProxy _callback;
        private readonly AndroidJavaObject _bridge;
        private long _nextRequestId;
        private bool _disposed;

        public AndroidBluetoothClassicAdapter()
        {
            _unityContext = SynchronizationContext.Current;
            _callback = new CallbackProxy(this);
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            {
                if (activity == null) throw new InvalidOperationException("Unity currentActivity is unavailable.");
                _bridge = new AndroidJavaObject(BridgeClassName, activity, _callback);
            }
        }

        public event Action<string, string, string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices()
        {
            ThrowIfDisposed();
            var values = _bridge.Call<string[]>("getBondedDevices") ?? Array.Empty<string>();
            var devices = new List<BluetoothClassicDevice>(values.Length);
            foreach (var value in values)
            {
                if (string.IsNullOrWhiteSpace(value)) continue;
                var split = value.IndexOf('\n');
                var address = split < 0 ? value : value.Substring(0, split);
                var name = split < 0 ? value : value.Substring(split + 1);
                devices.Add(new BluetoothClassicDevice(address, name));
            }
            return devices;
        }

        public Task StartServerAsync(string serviceName, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeAsync("startServer", cancellationToken, serviceName, serviceUuid);
        }

        public Task ConnectAsync(string deviceAddress, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeAsync("connect", cancellationToken, deviceAddress, serviceUuid);
        }

        public Task SendBytesAsync(string connectionId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeAsync("sendBytes", cancellationToken, connectionId, payload ?? Array.Empty<byte>());
        }

        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeAsync("broadcastBytes", cancellationToken, payload ?? Array.Empty<byte>());
        }

        public Task DisconnectAsync(string connectionId, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeAsync("disconnect", cancellationToken, connectionId);
        }

        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeAsync("stopAll", cancellationToken);
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            lock (_gate)
            {
                foreach (var operation in _operations.Values)
                    operation.TrySetException(new ObjectDisposedException(nameof(AndroidBluetoothClassicAdapter)));
                _operations.Clear();
            }
            _bridge.Dispose();
        }

        private async Task InvokeAsync(string method, CancellationToken cancellationToken, params object[] arguments)
        {
            ThrowIfDisposed();
            cancellationToken.ThrowIfCancellationRequested();
            var requestId = Interlocked.Increment(ref _nextRequestId);
            var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            lock (_gate) _operations.Add(requestId, completion);
            using (cancellationToken.Register(() => Cancel(requestId)))
            {
                try
                {
                    var callArguments = new object[arguments.Length + 1];
                    callArguments[0] = requestId;
                    Array.Copy(arguments, 0, callArguments, 1, arguments.Length);
                    _bridge.Call(method, callArguments);
                }
                catch
                {
                    lock (_gate) _operations.Remove(requestId);
                    throw;
                }
                await completion.Task.ConfigureAwait(false);
            }
        }

        private void Cancel(long requestId)
        {
            TaskCompletionSource<bool> completion = null;
            lock (_gate)
            {
                if (_operations.TryGetValue(requestId, out completion)) _operations.Remove(requestId);
            }
            if (completion != null) completion.TrySetCanceled();
        }

        private void Complete(long requestId, bool success, string error)
        {
            TaskCompletionSource<bool> completion = null;
            lock (_gate)
            {
                if (_operations.TryGetValue(requestId, out completion)) _operations.Remove(requestId);
            }
            if (completion == null) return;
            if (success) completion.TrySetResult(true);
            else completion.TrySetException(new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "Bluetooth Classic operation failed." : error));
        }

        private void Dispatch(Action action)
        {
            if (_unityContext == null || SynchronizationContext.Current == _unityContext) action();
            else _unityContext.Post(_ => action(), null);
        }

        private void ThrowIfDisposed()
        {
            if (_disposed) throw new ObjectDisposedException(nameof(AndroidBluetoothClassicAdapter));
        }

        private sealed class CallbackProxy : AndroidJavaProxy
        {
            private readonly AndroidBluetoothClassicAdapter _owner;

            public CallbackProxy(AndroidBluetoothClassicAdapter owner) : base(BridgeClassName + "$Callback") { _owner = owner; }

            public void onOperationResult(long requestId, bool success, string error) { _owner.Complete(requestId, success, error); }
            public void onConnected(string connectionId, string address, string name)
            {
                _owner.Dispatch(() => { var h = _owner.Connected; if (h != null) h(connectionId, address, name); });
            }
            public void onDisconnected(string connectionId)
            {
                _owner.Dispatch(() => { var h = _owner.Disconnected; if (h != null) h(connectionId); });
            }
            public void onBytesReceived(string connectionId, byte[] payload)
            {
                _owner.Dispatch(() => { var h = _owner.BytesReceived; if (h != null) h(connectionId, payload ?? Array.Empty<byte>()); });
            }
            public void onError(string operation, string message)
            {
                _owner.Dispatch(() => { var h = _owner.Faulted; if (h != null) h(new InvalidOperationException((operation ?? "bluetooth") + ": " + (message ?? "unknown error"))); });
            }
        }
    }
#else
    public sealed class AndroidBluetoothClassicAdapter : IBluetoothClassicPeerAdapter, IDisposable
    {
        public event Action<string, string, string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;
        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices() { throw new PlatformNotSupportedException("Bluetooth Classic Android adapter runs only on an Android player."); }
        public Task StartServerAsync(string serviceName, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task ConnectAsync(string deviceAddress, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task SendBytesAsync(string connectionId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task DisconnectAsync(string connectionId, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public void Dispose() { }
        private static Task Unsupported() { return Task.FromException(new PlatformNotSupportedException("Bluetooth Classic Android adapter runs only on an Android player.")); }
    }
#endif
}
