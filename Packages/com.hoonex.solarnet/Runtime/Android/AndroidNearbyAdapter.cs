using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Nearby;

#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine;
#endif

namespace SolarNet.Nearby.Android
{
#if UNITY_ANDROID && !UNITY_EDITOR
    public sealed class AndroidNearbyAdapter : INearbyPeerAdapter, IDisposable
    {
        private const string BridgeClassName = "com.hoonex.solarnet.nearby.SolarNearbyBridge";
        private readonly object _operationGate = new object();
        private readonly Dictionary<long, TaskCompletionSource<bool>> _operations = new Dictionary<long, TaskCompletionSource<bool>>();
        private readonly SynchronizationContext _unityContext;
        private readonly CallbackProxy _callback;
        private readonly AndroidJavaObject _bridge;
        private long _nextRequestId;
        private bool _disposed;

        public AndroidNearbyAdapter()
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

        public event Action<NearbyEndpoint> EndpointFound;
        public event Action<string> EndpointLost;
        public event Action<NearbyVerificationRequest> VerificationRequired;
        public event Action<string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public Task StartAdvertisingAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("startAdvertising", cancellationToken, serviceId, endpointName, StrategyName(strategy));
        }

        public Task StartDiscoveryAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("startDiscovery", cancellationToken, serviceId, endpointName, StrategyName(strategy));
        }

        public Task StopAdvertisingAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("stopAdvertising", cancellationToken);
        }

        public Task StopDiscoveryAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("stopDiscovery", cancellationToken);
        }

        public Task RequestConnectionAsync(string endpointId, string endpointName, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("requestConnection", cancellationToken, endpointId, endpointName);
        }

        public Task AcceptConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("acceptConnection", cancellationToken, endpointId);
        }

        public Task RejectConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("rejectConnection", cancellationToken, endpointId);
        }

        public Task DisconnectAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("disconnect", cancellationToken, endpointId);
        }

        public Task SendBytesAsync(string endpointId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("sendBytes", cancellationToken, endpointId, payload ?? Array.Empty<byte>());
        }

        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("broadcastBytes", cancellationToken, payload ?? Array.Empty<byte>());
        }

        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            return InvokeOperationAsync("stopAll", cancellationToken);
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            lock (_operationGate)
            {
                foreach (var operation in _operations.Values)
                    operation.TrySetException(new ObjectDisposedException(nameof(AndroidNearbyAdapter)));
                _operations.Clear();
            }
            _bridge.Dispose();
        }

        private async Task InvokeOperationAsync(string method, CancellationToken cancellationToken, params object[] arguments)
        {
            ThrowIfDisposed();
            cancellationToken.ThrowIfCancellationRequested();

            var requestId = Interlocked.Increment(ref _nextRequestId);
            var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            lock (_operationGate) _operations.Add(requestId, completion);

            using (cancellationToken.Register(() => CancelOperation(requestId)))
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
                    lock (_operationGate) _operations.Remove(requestId);
                    throw;
                }

                await completion.Task.ConfigureAwait(false);
            }
        }

        private void CancelOperation(long requestId)
        {
            TaskCompletionSource<bool> completion = null;
            lock (_operationGate)
            {
                if (_operations.TryGetValue(requestId, out completion)) _operations.Remove(requestId);
            }
            if (completion != null) completion.TrySetCanceled();
        }

        private void CompleteOperation(long requestId, bool success, string error)
        {
            TaskCompletionSource<bool> completion = null;
            lock (_operationGate)
            {
                if (_operations.TryGetValue(requestId, out completion)) _operations.Remove(requestId);
            }
            if (completion == null) return;
            if (success) completion.TrySetResult(true);
            else completion.TrySetException(new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "Nearby Android operation failed." : error));
        }

        private void Dispatch(Action action)
        {
            if (_unityContext == null || SynchronizationContext.Current == _unityContext)
                action();
            else
                _unityContext.Post(_ => action(), null);
        }

        private void RaiseFault(string operation, string message)
        {
            Dispatch(() =>
            {
                var handler = Faulted;
                if (handler != null) handler(new InvalidOperationException(operation + ": " + message));
            });
        }

        private static string StrategyName(NearbyConnectionStrategy strategy)
        {
            switch (strategy)
            {
                case NearbyConnectionStrategy.Cluster: return "CLUSTER";
                case NearbyConnectionStrategy.PointToPoint: return "POINT_TO_POINT";
                default: return "STAR";
            }
        }

        private void ThrowIfDisposed()
        {
            if (_disposed) throw new ObjectDisposedException(nameof(AndroidNearbyAdapter));
        }

        private sealed class CallbackProxy : AndroidJavaProxy
        {
            private readonly AndroidNearbyAdapter _owner;

            public CallbackProxy(AndroidNearbyAdapter owner) : base(BridgeClassName + "$Callback")
            {
                _owner = owner;
            }

            public void onOperationResult(long requestId, bool success, string error)
            {
                _owner.CompleteOperation(requestId, success, error);
            }

            public void onEndpointFound(string endpointId, string endpointName)
            {
                _owner.Dispatch(() =>
                {
                    var handler = _owner.EndpointFound;
                    if (handler != null) handler(new NearbyEndpoint(endpointId, endpointName));
                });
            }

            public void onEndpointLost(string endpointId)
            {
                _owner.Dispatch(() =>
                {
                    var handler = _owner.EndpointLost;
                    if (handler != null) handler(endpointId);
                });
            }

            public void onVerificationRequired(string endpointId, string endpointName, string authenticationDigits, bool incoming)
            {
                _owner.Dispatch(() =>
                {
                    var handler = _owner.VerificationRequired;
                    if (handler != null) handler(new NearbyVerificationRequest(endpointId, endpointName, authenticationDigits, incoming));
                });
            }

            public void onConnected(string endpointId)
            {
                _owner.Dispatch(() =>
                {
                    var handler = _owner.Connected;
                    if (handler != null) handler(endpointId);
                });
            }

            public void onDisconnected(string endpointId)
            {
                _owner.Dispatch(() =>
                {
                    var handler = _owner.Disconnected;
                    if (handler != null) handler(endpointId);
                });
            }

            public void onBytesReceived(string endpointId, byte[] payload)
            {
                _owner.Dispatch(() =>
                {
                    var handler = _owner.BytesReceived;
                    if (handler != null) handler(endpointId, payload ?? Array.Empty<byte>());
                });
            }

            public void onConnectionFailed(string endpointId, int statusCode)
            {
                _owner.RaiseFault("connection", endpointId + " status=" + statusCode);
            }

            public void onError(string operation, string message)
            {
                _owner.RaiseFault(operation ?? "nearby", message ?? "unknown error");
            }
        }
    }
#else
    public sealed class AndroidNearbyAdapter : INearbyPeerAdapter, IDisposable
    {
        public event Action<NearbyEndpoint> EndpointFound;
        public event Action<string> EndpointLost;
        public event Action<NearbyVerificationRequest> VerificationRequired;
        public event Action<string> Connected;
        public event Action<string> Disconnected;
        public event Action<string, byte[]> BytesReceived;
        public event Action<Exception> Faulted;

        public Task StartAdvertisingAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task StartDiscoveryAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task StopAdvertisingAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task StopDiscoveryAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task RequestConnectionAsync(string endpointId, string endpointName, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task AcceptConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task RejectConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task DisconnectAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task SendBytesAsync(string endpointId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken)) { return Unsupported(); }
        public void Dispose() { }

        private static Task Unsupported()
        {
            return Task.FromException(new PlatformNotSupportedException("AndroidNearbyAdapter runs only on an Android player, not in the Unity Editor or another platform."));
        }
    }
#endif
}
