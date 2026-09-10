using System;
using System.Collections.Generic;
using SolarNet.Turns;

internal static class Program
{
    private static int Main()
    {
        var tests = new Action[]
        {
            SequenceFrontierSurvivesRestore,
            CapturedFrontierIsDefensive,
            InvalidFrontiersAreRejected,
            LegacyRestoreRemainsCompatible
        };

        var passed = 0;
        foreach (var test in tests)
        {
            try
            {
                test();
                passed++;
                Console.WriteLine("PASS " + test.Method.Name);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FAIL " + test.Method.Name + ": " + ex);
                return 1;
            }
        }

        Console.WriteLine("Forge v4 SolarNet sequence-frontier tests passed: " + passed + "/" + tests.Length);
        return 0;
    }

    private static void SequenceFrontierSurvivesRestore()
    {
        var players = new[] { "host", "peer-2" };
        var coordinator = new TurnCoordinator(players);
        SolarTurnCommit commit;
        SolarTurnRejectReason reason;

        True(coordinator.TryCommit(new SolarTurnAction("host", 0, 5, "move", Array.Empty<byte>()), out commit, out reason), "host turn");
        True(coordinator.TryCommit(new SolarTurnAction("peer-2", 1, 8, "attack", Array.Empty<byte>()), out commit, out reason), "peer turn");

        var frontier = coordinator.CaptureAcceptedSequences();
        var restored = TurnCoordinator.Restore(players, 2, "host", 2, frontier);

        False(restored.TryCommit(new SolarTurnAction("host", 2, 5, "move", Array.Empty<byte>()), out commit, out reason), "restored duplicate must reject");
        Equal(SolarTurnRejectReason.DuplicateOrOutOfOrderSequence, reason, "restored duplicate reason");
        True(restored.TryCommit(new SolarTurnAction("host", 2, 6, "move", Array.Empty<byte>()), out commit, out reason), "newer sequence after restore");
    }

    private static void CapturedFrontierIsDefensive()
    {
        var coordinator = new TurnCoordinator(new[] { "host", "peer-2" });
        SolarTurnCommit commit;
        SolarTurnRejectReason reason;

        True(coordinator.TryCommit(new SolarTurnAction("host", 0, 5, "move", Array.Empty<byte>()), out commit, out reason), "host first turn");
        var captured = coordinator.CaptureAcceptedSequences();
        Equal(5L, captured["host"], "captured host frontier");

        True(coordinator.TryCommit(new SolarTurnAction("peer-2", 1, 8, "attack", Array.Empty<byte>()), out commit, out reason), "peer turn");
        True(coordinator.TryCommit(new SolarTurnAction("host", 2, 6, "move", Array.Empty<byte>()), out commit, out reason), "host later turn");

        Equal(5L, captured["host"], "captured frontier must not alias live coordinator state");
        False(captured.ContainsKey("peer-2"), "captured frontier must not gain later peer entries");
    }

    private static void InvalidFrontiersAreRejected()
    {
        var players = new[] { "host", "peer-2" };

        Throws<ArgumentException>(
            () => TurnCoordinator.Restore(players, 2, "host", 2, new Dictionary<string, long> { { "intruder", 1 } }),
            "unknown frontier player");

        Throws<ArgumentOutOfRangeException>(
            () => TurnCoordinator.Restore(players, 2, "host", 2, new Dictionary<string, long> { { "host", -1 } }),
            "negative frontier sequence");
    }

    private static void LegacyRestoreRemainsCompatible()
    {
        var restored = TurnCoordinator.Restore(new[] { "host", "peer-2" }, 2, "host", 2);
        SolarTurnCommit commit;
        SolarTurnRejectReason reason;

        True(restored.TryCommit(new SolarTurnAction("host", 2, 0, "move", Array.Empty<byte>()), out commit, out reason), "legacy restore overload remains usable");
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
}
