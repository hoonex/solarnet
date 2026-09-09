package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic turn-based air-hockey/combat rules used by Pulse Hockey 3D.
 *
 * The game deliberately has a finite turn cap, escalating Overdrive goal width,
 * alternating initiative, and a loser-serves-next rule so a match cannot become
 * an endless avoidance loop and does not structurally reward the opening side.
 */
public final class PulseHockeyGame {
    public static final float HALF_WIDTH = 5.0f;
    public static final float HALF_LENGTH = 9.0f;
    public static final float PUCK_RADIUS = 0.42f;
    public static final float MALLET_RADIUS = 0.72f;
    public static final int MAX_ENERGY = 5;
    public static final int WIN_SCORE = 5;
    public static final int MAX_TURNS = 36;

    private static final float DT = 1f / 60f;
    private static final int MAX_STEPS = 240;
    private static final float PUCK_FRICTION = 0.9925f;
    private static final float MALLET_FRICTION = 0.982f;

    public enum Player {
        SUN(1),
        MOON(-1),
        NONE(0);

        final int direction;

        Player(int direction) {
            this.direction = direction;
        }

        public Player opponent() {
            if (this == SUN) return MOON;
            if (this == MOON) return SUN;
            return NONE;
        }
    }

    public enum Tactic {
        STRIKE,
        POWER,
        GUARD,
        COUNTER
    }

    public enum Stance {
        NONE,
        GUARD,
        COUNTER
    }

    public static final class Action {
        public final Tactic tactic;
        public final float x;
        public final float z;
        public final float power;

        public Action(Tactic tactic, float x, float z, float power) {
            if (tactic == null) throw new IllegalArgumentException("tactic is required");
            if (!Float.isFinite(x) || !Float.isFinite(z) || !Float.isFinite(power))
                throw new IllegalArgumentException("action values must be finite");
            this.tactic = tactic;
            this.x = x;
            this.z = z;
            this.power = clamp(power, 0f, 1f);
        }

        public static Action strike(float x, float z, float power) {
            return new Action(Tactic.STRIKE, x, z, power);
        }

        public static Action power(float x, float z, float power) {
            return new Action(Tactic.POWER, x, z, power);
        }

        public static Action guard(float x) {
            return new Action(Tactic.GUARD, x, 0f, 0f);
        }

        public static Action counter(float x) {
            return new Action(Tactic.COUNTER, x, 0f, 0f);
        }
    }

    public static final class MotionFrame {
        public final float puckX;
        public final float puckZ;
        public final float sunX;
        public final float sunZ;
        public final float moonX;
        public final float moonZ;

        MotionFrame(float puckX, float puckZ, float sunX, float sunZ, float moonX, float moonZ) {
            this.puckX = puckX;
            this.puckZ = puckZ;
            this.sunX = sunX;
            this.sunZ = sunZ;
            this.moonX = moonX;
            this.moonZ = moonZ;
        }
    }

    public static final class TurnResult {
        public final boolean accepted;
        public final boolean goal;
        public final boolean counterTriggered;
        public final Player scorer;
        public final Player winner;
        public final boolean draw;
        public final String message;
        public final List<MotionFrame> frames;

        TurnResult(
                boolean accepted,
                boolean goal,
                boolean counterTriggered,
                Player scorer,
                Player winner,
                boolean draw,
                String message,
                List<MotionFrame> frames) {
            this.accepted = accepted;
            this.goal = goal;
            this.counterTriggered = counterTriggered;
            this.scorer = scorer;
            this.winner = winner;
            this.draw = draw;
            this.message = message;
            this.frames = frames == null ? Collections.emptyList() : Collections.unmodifiableList(frames);
        }
    }

    private final long matchSeed;
    private final Player openingPlayer;

    private int turnNumber;
    private int noGoalTurns;
    private int sunScore;
    private int moonScore;
    private int sunEnergy;
    private int moonEnergy;

    private float puckX;
    private float puckZ;
    private float sunX;
    private float sunZ;
    private float moonX;
    private float moonZ;

    private Player activePlayer;
    private Player winner;
    private boolean draw;
    private Stance sunStance;
    private Stance moonStance;

    public PulseHockeyGame(long matchSeed) {
        this.matchSeed = matchSeed;
        this.openingPlayer = chooseOpeningPlayer(matchSeed);
        resetInternal(openingPlayer);
    }

    public PulseHockeyGame(PulseHockeyGame source) {
        if (source == null) throw new IllegalArgumentException("source is required");
        this.matchSeed = source.matchSeed;
        this.openingPlayer = source.openingPlayer;
        this.turnNumber = source.turnNumber;
        this.noGoalTurns = source.noGoalTurns;
        this.sunScore = source.sunScore;
        this.moonScore = source.moonScore;
        this.sunEnergy = source.sunEnergy;
        this.moonEnergy = source.moonEnergy;
        this.puckX = source.puckX;
        this.puckZ = source.puckZ;
        this.sunX = source.sunX;
        this.sunZ = source.sunZ;
        this.moonX = source.moonX;
        this.moonZ = source.moonZ;
        this.activePlayer = source.activePlayer;
        this.winner = source.winner;
        this.draw = source.draw;
        this.sunStance = source.sunStance;
        this.moonStance = source.moonStance;
    }

    public void reset() {
        resetInternal(openingPlayer);
    }

    private void resetInternal(Player starter) {
        turnNumber = 0;
        noGoalTurns = 0;
        sunScore = 0;
        moonScore = 0;
        sunEnergy = 4;
        moonEnergy = 4;
        activePlayer = starter;
        winner = Player.NONE;
        draw = false;
        sunStance = Stance.NONE;
        moonStance = Stance.NONE;
        resetArenaPositions();
    }

    private void resetArenaPositions() {
        puckX = 0f;
        puckZ = 0f;
        sunX = 0f;
        sunZ = -5.8f;
        moonX = 0f;
        moonZ = 5.8f;
    }

    public long getMatchSeed() { return matchSeed; }
    public Player getOpeningPlayer() { return openingPlayer; }
    public Player getActivePlayer() { return activePlayer; }
    public Player getWinner() { return winner; }
    public boolean isDraw() { return draw; }
    public boolean isFinished() { return winner != Player.NONE || draw; }
    public int getTurnNumber() { return turnNumber; }
    public int getNoGoalTurns() { return noGoalTurns; }
    public int getSunScore() { return sunScore; }
    public int getMoonScore() { return moonScore; }
    public int getScore(Player player) { return player == Player.SUN ? sunScore : player == Player.MOON ? moonScore : 0; }
    public int getEnergy(Player player) { return player == Player.SUN ? sunEnergy : player == Player.MOON ? moonEnergy : 0; }
    public float getPuckX() { return puckX; }
    public float getPuckZ() { return puckZ; }
    public float getMalletX(Player player) { return player == Player.SUN ? sunX : moonX; }
    public float getMalletZ(Player player) { return player == Player.SUN ? sunZ : moonZ; }
    public Stance getStance(Player player) { return player == Player.SUN ? sunStance : player == Player.MOON ? moonStance : Stance.NONE; }

    public float getGoalHalfWidth() {
        float extra = Math.max(0, noGoalTurns - 3) * 0.28f;
        return Math.min(4.35f, 2.10f + extra);
    }

    public int getOverdriveLevel() {
        return Math.max(0, noGoalTurns - 3);
    }

    public boolean canUse(Player player, Tactic tactic) {
        if (player == Player.NONE || tactic == null || isFinished()) return false;
        return getEnergy(player) >= tacticCost(tactic);
    }

    public TurnResult apply(Action action) {
        return apply(action, true);
    }

    /** Apply one deterministic turn. Record frames only for user-visible animation. */
    public TurnResult apply(Action action, boolean recordFrames) {
        if (action == null)
            return rejected("Action is required");
        if (isFinished())
            return rejected("Match already finished");

        Player actor = activePlayer;
        Player defender = actor.opponent();
        int cost = tacticCost(action.tactic);
        if (getEnergy(actor) < cost)
            return rejected("Not enough energy");

        setStance(actor, Stance.NONE); // the actor's previous defensive stance has expired.
        addEnergy(actor, -cost);

        if (action.tactic == Tactic.GUARD || action.tactic == Tactic.COUNTER) {
            float targetX = clamp(action.x, -3.9f, 3.9f);
            setMalletPosition(actor, targetX, actor == Player.SUN ? -6.75f : 6.75f);
            if (action.tactic == Tactic.GUARD) {
                setStance(actor, Stance.GUARD);
                addEnergy(actor, 2);
            } else {
                setStance(actor, Stance.COUNTER);
                addEnergy(actor, 1);
            }
            noGoalTurns++;
            turnNumber++;
            activePlayer = defender;
            resolveTurnLimit();
            return new TurnResult(
                    true,
                    false,
                    false,
                    Player.NONE,
                    winner,
                    draw,
                    action.tactic == Tactic.GUARD ? "Guard armed" : "Counter armed",
                    oneCurrentFrame(recordFrames));
        }

        float dirX = action.x;
        float dirZ = action.z;
        float directionMagnitude = hypot(dirX, dirZ);
        if (directionMagnitude < 0.001f) {
            dirX = 0f;
            dirZ = actor == Player.SUN ? 1f : -1f;
            directionMagnitude = 1f;
        }
        dirX /= directionMagnitude;
        dirZ /= directionMagnitude;

        float overdriveMultiplier = 1f + 0.055f * getOverdriveLevel();
        float baseSpeed = 7.2f + 4.2f * action.power;
        if (action.tactic == Tactic.POWER) baseSpeed *= 1.55f;
        baseSpeed *= overdriveMultiplier;

        float sunVx = 0f;
        float sunVz = 0f;
        float moonVx = 0f;
        float moonVz = 0f;
        if (actor == Player.SUN) {
            sunVx = dirX * baseSpeed;
            sunVz = dirZ * baseSpeed;
        } else {
            moonVx = dirX * baseSpeed;
            moonVz = dirZ * baseSpeed;
        }

        float puckVx = 0f;
        float puckVz = 0f;
        boolean counterTriggered = false;
        Player scorer = Player.NONE;
        List<MotionFrame> frames = recordFrames ? new ArrayList<>() : null;
        if (recordFrames) frames.add(snapshot());

        for (int step = 0; step < MAX_STEPS; step++) {
            sunX += sunVx * DT;
            sunZ += sunVz * DT;
            moonX += moonVx * DT;
            moonZ += moonVz * DT;

            float[] sunVelocity = constrainMallet(Player.SUN, sunVx, sunVz);
            sunVx = sunVelocity[0];
            sunVz = sunVelocity[1];
            float[] moonVelocity = constrainMallet(Player.MOON, moonVx, moonVz);
            moonVx = moonVelocity[0];
            moonVz = moonVelocity[1];

            puckX += puckVx * DT;
            puckZ += puckVz * DT;

            float sideLimit = HALF_WIDTH - PUCK_RADIUS;
            if (puckX < -sideLimit) {
                puckX = -sideLimit;
                puckVx = Math.abs(puckVx) * 0.92f;
            } else if (puckX > sideLimit) {
                puckX = sideLimit;
                puckVx = -Math.abs(puckVx) * 0.92f;
            }

            float goalHalfWidth = getGoalHalfWidth();
            if (puckZ > HALF_LENGTH) {
                if (Math.abs(puckX) < goalHalfWidth) {
                    scorer = Player.SUN;
                    break;
                }
                puckZ = HALF_LENGTH;
                puckVz = -Math.abs(puckVz) * 0.90f;
            } else if (puckZ < -HALF_LENGTH) {
                if (Math.abs(puckX) < goalHalfWidth) {
                    scorer = Player.MOON;
                    break;
                }
                puckZ = -HALF_LENGTH;
                puckVz = Math.abs(puckVz) * 0.90f;
            }

            CollisionResult sunCollision = collideWithMallet(
                    Player.SUN,
                    puckVx,
                    puckVz,
                    sunVx,
                    sunVz,
                    defender);
            puckVx = sunCollision.puckVx;
            puckVz = sunCollision.puckVz;
            sunVx = sunCollision.malletVx;
            sunVz = sunCollision.malletVz;
            counterTriggered |= sunCollision.counterTriggered;

            CollisionResult moonCollision = collideWithMallet(
                    Player.MOON,
                    puckVx,
                    puckVz,
                    moonVx,
                    moonVz,
                    defender);
            puckVx = moonCollision.puckVx;
            puckVz = moonCollision.puckVz;
            moonVx = moonCollision.malletVx;
            moonVz = moonCollision.malletVz;
            counterTriggered |= moonCollision.counterTriggered;

            puckVx *= PUCK_FRICTION;
            puckVz *= PUCK_FRICTION;
            sunVx *= MALLET_FRICTION;
            sunVz *= MALLET_FRICTION;
            moonVx *= MALLET_FRICTION;
            moonVz *= MALLET_FRICTION;

            if (recordFrames && step % 4 == 0) frames.add(snapshot());

            if (step > 70
                    && Math.abs(puckVx) + Math.abs(puckVz) < 0.055f
                    && Math.abs(sunVx) + Math.abs(sunVz) < 0.055f
                    && Math.abs(moonVx) + Math.abs(moonVz) < 0.055f) {
                break;
            }
        }

        setStance(defender, Stance.NONE); // defensive tactic lasts exactly one opposing attack.
        addEnergy(actor, 1);
        turnNumber++;

        if (scorer != Player.NONE) {
            addScore(scorer, 1);
            addEnergy(scorer, 1);
            noGoalTurns = 0;
            resetArenaPositions();
            activePlayer = scorer.opponent(); // conceding player serves next.
        } else {
            noGoalTurns++;
            activePlayer = defender;
        }

        resolveWinner();
        resolveTurnLimit();
        if (recordFrames) frames.add(snapshot());

        String message;
        if (scorer != Player.NONE) message = scorer + " scored";
        else if (counterTriggered) message = "Counter reflected the puck";
        else message = action.tactic == Tactic.POWER ? "Power strike" : "Strike";

        return new TurnResult(
                true,
                scorer != Player.NONE,
                counterTriggered,
                scorer,
                winner,
                draw,
                message,
                frames);
    }

    private CollisionResult collideWithMallet(
            Player mallet,
            float puckVx,
            float puckVz,
            float malletVx,
            float malletVz,
            Player currentDefender) {
        float mx = getMalletX(mallet);
        float mz = getMalletZ(mallet);
        Stance stance = getStance(mallet);
        float effectiveRadius = MALLET_RADIUS * (stance == Stance.GUARD ? 1.28f : 1f);
        float dx = puckX - mx;
        float dz = puckZ - mz;
        float distance = hypot(dx, dz);
        float minDistance = PUCK_RADIUS + effectiveRadius;
        if (distance <= 0.0001f || distance >= minDistance)
            return new CollisionResult(puckVx, puckVz, malletVx, malletVz, false);

        float nx = dx / distance;
        float nz = dz / distance;
        float overlap = minDistance - distance;
        puckX += nx * overlap;
        puckZ += nz * overlap;

        float relativeNormal = (puckVx - malletVx) * nx + (puckVz - malletVz) * nz;
        if (relativeNormal >= 0f)
            return new CollisionResult(puckVx, puckVz, malletVx, malletVz, false);

        // Puck mass = 1, mallet mass = 2. Restitution intentionally high for arcade feel.
        float impulse = -(1f + 0.88f) * relativeNormal / 1.5f;
        puckVx += impulse * nx;
        puckVz += impulse * nz;
        malletVx -= impulse * nx * 0.5f;
        malletVz -= impulse * nz * 0.5f;

        boolean counterTriggered = false;
        if (mallet == currentDefender) {
            if (stance == Stance.GUARD) {
                puckVx *= 0.72f;
                puckVz *= 0.72f;
            } else if (stance == Stance.COUNTER) {
                puckVx *= 1.38f;
                puckVz *= 1.38f;
                counterTriggered = true;
            }
        }
        return new CollisionResult(puckVx, puckVz, malletVx, malletVz, counterTriggered);
    }

    private float[] constrainMallet(Player player, float vx, float vz) {
        float xLimit = HALF_WIDTH - MALLET_RADIUS;
        float x = getMalletX(player);
        float z = getMalletZ(player);
        if (x < -xLimit) {
            x = -xLimit;
            vx = Math.abs(vx) * 0.55f;
        } else if (x > xLimit) {
            x = xLimit;
            vx = -Math.abs(vx) * 0.55f;
        }

        float minZ;
        float maxZ;
        if (player == Player.SUN) {
            minZ = -HALF_LENGTH + MALLET_RADIUS;
            maxZ = 1.80f;
        } else {
            minZ = -1.80f;
            maxZ = HALF_LENGTH - MALLET_RADIUS;
        }
        if (z < minZ) {
            z = minZ;
            vz = Math.abs(vz) * 0.55f;
        } else if (z > maxZ) {
            z = maxZ;
            vz = -Math.abs(vz) * 0.55f;
        }
        setMalletPosition(player, x, z);
        return new float[]{vx, vz};
    }

    private void resolveWinner() {
        if (sunScore >= WIN_SCORE) winner = Player.SUN;
        else if (moonScore >= WIN_SCORE) winner = Player.MOON;
    }

    private void resolveTurnLimit() {
        if (winner != Player.NONE || draw || turnNumber < MAX_TURNS) return;
        if (sunScore > moonScore) winner = Player.SUN;
        else if (moonScore > sunScore) winner = Player.MOON;
        else draw = true;
    }

    private TurnResult rejected(String message) {
        return new TurnResult(false, false, false, Player.NONE, winner, draw, message, Collections.emptyList());
    }

    private List<MotionFrame> oneCurrentFrame(boolean recordFrames) {
        if (!recordFrames) return Collections.emptyList();
        List<MotionFrame> frames = new ArrayList<>(1);
        frames.add(snapshot());
        return frames;
    }

    private MotionFrame snapshot() {
        return new MotionFrame(puckX, puckZ, sunX, sunZ, moonX, moonZ);
    }

    private void addScore(Player player, int delta) {
        if (player == Player.SUN) sunScore += delta;
        else if (player == Player.MOON) moonScore += delta;
    }

    private void addEnergy(Player player, int delta) {
        if (player == Player.SUN) sunEnergy = clampInt(sunEnergy + delta, 0, MAX_ENERGY);
        else if (player == Player.MOON) moonEnergy = clampInt(moonEnergy + delta, 0, MAX_ENERGY);
    }

    private void setStance(Player player, Stance stance) {
        if (player == Player.SUN) sunStance = stance;
        else if (player == Player.MOON) moonStance = stance;
    }

    private void setMalletPosition(Player player, float x, float z) {
        if (player == Player.SUN) {
            sunX = x;
            sunZ = z;
        } else if (player == Player.MOON) {
            moonX = x;
            moonZ = z;
        }
    }

    private static int tacticCost(Tactic tactic) {
        switch (tactic) {
            case POWER: return 3;
            case COUNTER: return 1;
            case STRIKE: return 1;
            case GUARD:
            default: return 0;
        }
    }

    private static Player chooseOpeningPlayer(long seed) {
        long mixed = seed;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53l;
        mixed ^= mixed >>> 33;
        return (mixed & 1L) == 0L ? Player.SUN : Player.MOON;
    }

    private static float hypot(float x, float z) {
        return (float) Math.sqrt(x * x + z * z);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class CollisionResult {
        final float puckVx;
        final float puckVz;
        final float malletVx;
        final float malletVz;
        final boolean counterTriggered;

        CollisionResult(float puckVx, float puckVz, float malletVx, float malletVz, boolean counterTriggered) {
            this.puckVx = puckVx;
            this.puckVz = puckVz;
            this.malletVx = malletVx;
            this.malletVz = malletVz;
            this.counterTriggered = counterTriggered;
        }
    }
}
