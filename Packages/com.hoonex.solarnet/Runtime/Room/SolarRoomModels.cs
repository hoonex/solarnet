using System;
using System.Collections.Generic;
using SolarNet.Turns;

namespace SolarNet.Room
{
    public enum SolarRoomPhase : byte
    {
        Joining = 0,
        Lobby = 1,
        Reconnecting = 2,
        Playing = 3,
        Closed = 4
    }

    public enum SolarHostDisconnectPolicy
    {
        CloseRoom = 0,
        WaitForReconnect = 1
    }

    public enum SolarRoomJoinRejectReason : byte
    {
        ProtocolMismatch = 1,
        CompatibilityMismatch = 2,
        RoomFull = 3,
        AlreadyStarted = 4,
        IdentityMismatch = 5,
        InvalidRequest = 6
    }

    public enum SolarRoomCloseReason : byte
    {
        HostDisconnected = 1,
        HostClosed = 2,
        LocalLeave = 3
    }

    public sealed class SolarRoomPlayer
    {
        public SolarRoomPlayer(int slot, string peerId, string displayName, bool isReady, bool isConnected)
        {
            if (slot < 0) throw new ArgumentOutOfRangeException(nameof(slot));
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));
            if (string.IsNullOrWhiteSpace(displayName)) throw new ArgumentException("Display name is required.", nameof(displayName));
            Slot = slot;
            PeerId = peerId;
            DisplayName = displayName;
            IsReady = isReady;
            IsConnected = isConnected;
        }

        public int Slot { get; private set; }
        public string PeerId { get; private set; }
        public string DisplayName { get; private set; }
        public bool IsReady { get; private set; }
        public bool IsConnected { get; private set; }

        internal SolarRoomPlayer WithState(bool isReady, bool isConnected)
        {
            return new SolarRoomPlayer(Slot, PeerId, DisplayName, isReady, isConnected);
        }
    }

    public sealed class SolarRoomSnapshot
    {
        public SolarRoomSnapshot(
            long revision,
            string roomId,
            string roomName,
            string hostPeerId,
            string compatibilityKey,
            int maxPlayers,
            SolarRoomPhase phase,
            string gameSessionId,
            SolarRoomPlayer[] players)
        {
            if (revision < 0) throw new ArgumentOutOfRangeException(nameof(revision));
            if (string.IsNullOrWhiteSpace(roomId)) throw new ArgumentException("Room ID is required.", nameof(roomId));
            if (string.IsNullOrWhiteSpace(roomName)) throw new ArgumentException("Room name is required.", nameof(roomName));
            if (string.IsNullOrWhiteSpace(hostPeerId)) throw new ArgumentException("Host peer ID is required.", nameof(hostPeerId));
            if (string.IsNullOrWhiteSpace(compatibilityKey)) throw new ArgumentException("Compatibility key is required.", nameof(compatibilityKey));
            if (maxPlayers < 2 || maxPlayers > 16) throw new ArgumentOutOfRangeException(nameof(maxPlayers));

            Revision = revision;
            RoomId = roomId;
            RoomName = roomName;
            HostPeerId = hostPeerId;
            CompatibilityKey = compatibilityKey;
            MaxPlayers = maxPlayers;
            Phase = phase;
            GameSessionId = gameSessionId ?? string.Empty;
            Players = players == null ? Array.Empty<SolarRoomPlayer>() : (SolarRoomPlayer[])players.Clone();
        }

        public long Revision { get; private set; }
        public string RoomId { get; private set; }
        public string RoomName { get; private set; }
        public string HostPeerId { get; private set; }
        public string CompatibilityKey { get; private set; }
        public int MaxPlayers { get; private set; }
        public SolarRoomPhase Phase { get; private set; }
        public string GameSessionId { get; private set; }
        public SolarRoomPlayer[] Players { get; private set; }
    }

    public sealed class SolarRoomJoinRejection
    {
        public SolarRoomJoinRejection(SolarRoomJoinRejectReason reason)
        {
            Reason = reason;
        }

        public SolarRoomJoinRejectReason Reason { get; private set; }
    }

    public sealed class SolarRoomClosed
    {
        public SolarRoomClosed(SolarRoomCloseReason reason)
        {
            Reason = reason;
        }

        public SolarRoomCloseReason Reason { get; private set; }
    }

    public sealed class SolarGameStartInfo
    {
        public SolarGameStartInfo(string gameSessionId, string hostPeerId, string[] playerIds)
        {
            if (string.IsNullOrWhiteSpace(gameSessionId)) throw new ArgumentException("Game session ID is required.", nameof(gameSessionId));
            if (string.IsNullOrWhiteSpace(hostPeerId)) throw new ArgumentException("Host peer ID is required.", nameof(hostPeerId));
            if (playerIds == null || playerIds.Length < 2) throw new ArgumentException("At least two players are required.", nameof(playerIds));
            GameSessionId = gameSessionId;
            HostPeerId = hostPeerId;
            PlayerIds = (string[])playerIds.Clone();
        }

        public string GameSessionId { get; private set; }
        public string HostPeerId { get; private set; }
        public string[] PlayerIds { get; private set; }

        public TurnCoordinator CreateHostTurnCoordinator()
        {
            return new TurnCoordinator(PlayerIds);
        }
    }

    public sealed class SolarRoomOptions
    {
        public SolarRoomOptions(
            string roomId,
            string hostPeerId,
            string localDisplayName,
            string compatibilityKey,
            string roomName = "SolarNet Room",
            int maxPlayers = 4,
            SolarHostDisconnectPolicy hostDisconnectPolicy = SolarHostDisconnectPolicy.CloseRoom)
        {
            if (string.IsNullOrWhiteSpace(roomId)) throw new ArgumentException("Room ID is required.", nameof(roomId));
            if (string.IsNullOrWhiteSpace(hostPeerId)) throw new ArgumentException("Host peer ID is required.", nameof(hostPeerId));
            if (string.IsNullOrWhiteSpace(localDisplayName)) throw new ArgumentException("Local display name is required.", nameof(localDisplayName));
            if (string.IsNullOrWhiteSpace(compatibilityKey)) throw new ArgumentException("Compatibility key is required.", nameof(compatibilityKey));
            if (string.IsNullOrWhiteSpace(roomName)) throw new ArgumentException("Room name is required.", nameof(roomName));
            if (maxPlayers < 2 || maxPlayers > 16) throw new ArgumentOutOfRangeException(nameof(maxPlayers));

            RoomId = roomId;
            HostPeerId = hostPeerId;
            LocalDisplayName = localDisplayName;
            CompatibilityKey = compatibilityKey;
            RoomName = roomName;
            MaxPlayers = maxPlayers;
            HostDisconnectPolicy = hostDisconnectPolicy;
        }

        public string RoomId { get; private set; }
        public string HostPeerId { get; private set; }
        public string LocalDisplayName { get; private set; }
        public string CompatibilityKey { get; private set; }
        public string RoomName { get; private set; }
        public int MaxPlayers { get; private set; }
        public SolarHostDisconnectPolicy HostDisconnectPolicy { get; private set; }
    }
}
