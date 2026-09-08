using System;
using System.Collections.Generic;
using SolarNet.Turns;

namespace SolarNet.State
{
    internal sealed class SolarActionJournal
    {
        private readonly int _capacity;
        private readonly LinkedList<SolarTurnCommit> _entries = new LinkedList<SolarTurnCommit>();

        public SolarActionJournal(int capacity)
        {
            if (capacity < 1) throw new ArgumentOutOfRangeException(nameof(capacity));
            _capacity = capacity;
        }

        public int Count { get { return _entries.Count; } }

        public void Add(SolarTurnCommit commit)
        {
            if (commit == null) throw new ArgumentNullException(nameof(commit));
            if (_entries.Last != null && commit.CommittedTurnIndex <= _entries.Last.Value.CommittedTurnIndex)
                throw new InvalidOperationException("SolarNet action journal requires strictly increasing turn indices.");

            _entries.AddLast(commit);
            while (_entries.Count > _capacity)
                _entries.RemoveFirst();
        }

        public bool TryGetRange(long nextTurnIndex, long authoritativeNextTurnIndex, out List<SolarTurnCommit> commits)
        {
            commits = new List<SolarTurnCommit>();
            if (nextTurnIndex < 0 || authoritativeNextTurnIndex < 0 || nextTurnIndex > authoritativeNextTurnIndex)
                return false;
            if (nextTurnIndex == authoritativeNextTurnIndex)
                return true;

            var expected = nextTurnIndex;
            for (var node = _entries.First; node != null; node = node.Next)
            {
                var commit = node.Value;
                if (commit.CommittedTurnIndex < nextTurnIndex) continue;
                if (commit.CommittedTurnIndex != expected) return false;
                commits.Add(commit);
                expected++;
                if (expected == authoritativeNextTurnIndex) return true;
            }
            return false;
        }
    }
}
