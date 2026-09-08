using System;

namespace SolarNet.Protocol
{
    public enum SolarPacketType : byte
    {
        Hello = 1,
        TurnAction = 2,
        TurnCommitted = 3,
        TurnRejected = 4,
        StateDigest = 5,
        Snapshot = 6,
        ResyncRequest = 7,
        ResyncUnavailable = 8,

        RoomJoinRequest = 20,
        RoomJoinRejected = 21,
        RoomState = 22,
        RoomReady = 23,
        RoomLeave = 24,
        RoomClose = 25
    }

    public sealed class SolarPacket
    {
        public const byte CurrentProtocolVersion = 1;

        public SolarPacket(
            SolarPacketType type,
            string sessionId,
            string senderId,
            long sequence,
            long turnIndex,
            byte[] payload,
            string stateHash = "")
        {
            if (string.IsNullOrWhiteSpace(sessionId)) throw new ArgumentException("Session ID is required.", nameof(sessionId));
            if (string.IsNullOrWhiteSpace(senderId)) throw new ArgumentException("Sender ID is required.", nameof(senderId));
            if (sequence < 0) throw new ArgumentOutOfRangeException(nameof(sequence));
            if (turnIndex < 0) throw new ArgumentOutOfRangeException(nameof(turnIndex));

            Type = type;
            SessionId = sessionId;
            SenderId = senderId;
            Sequence = sequence;
            TurnIndex = turnIndex;
            Payload = payload ?? Array.Empty<byte>();
            StateHash = stateHash ?? string.Empty;
        }

        public byte ProtocolVersion { get { return CurrentProtocolVersion; } }
        public SolarPacketType Type { get; private set; }
        public string SessionId { get; private set; }
        public string SenderId { get; private set; }
        public long Sequence { get; private set; }
        public long TurnIndex { get; private set; }
        public byte[] Payload { get; private set; }
        public string StateHash { get; private set; }
    }
}
