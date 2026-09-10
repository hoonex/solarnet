package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameEvent;
import com.hoonex.nightshift.core.GameSnapshot;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/** Small procedural game-audio layer. No network or external assets are required. */
final class NightshiftAudio {
    private static final int SAMPLE_RATE = 22050;

    private final AudioTrack ambience;
    private final AudioTrack heartbeat;
    private final AudioTrack footstep;
    private final AudioTrack pickup;
    private final AudioTrack breaker;
    private final AudioTrack danger;
    private final AudioTrack escape;

    private long lastHeartbeatMs;
    private long lastFootstepMs;
    private boolean started;

    NightshiftAudio() {
        ambience = makeTrack(buildAmbience(), true, AudioAttributes.CONTENT_TYPE_MUSIC);
        heartbeat = makeTrack(buildHeartbeat(), false, AudioAttributes.CONTENT_TYPE_SONIFICATION);
        footstep = makeTrack(buildFootstep(), false, AudioAttributes.CONTENT_TYPE_SONIFICATION);
        pickup = makeTrack(buildPickup(), false, AudioAttributes.CONTENT_TYPE_SONIFICATION);
        breaker = makeTrack(buildBreaker(), false, AudioAttributes.CONTENT_TYPE_SONIFICATION);
        danger = makeTrack(buildDanger(), false, AudioAttributes.CONTENT_TYPE_SONIFICATION);
        escape = makeTrack(buildEscape(), false, AudioAttributes.CONTENT_TYPE_SONIFICATION);
    }

    void start() {
        started = true;
        if (ambience != null && ambience.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try { ambience.play(); } catch (RuntimeException ignored) {}
        }
    }

    void pause() {
        started = false;
        if (ambience != null) {
            try { ambience.pause(); } catch (RuntimeException ignored) {}
        }
    }

    void onFrame(GameSnapshot.PlayerView me, boolean moving, boolean sprinting, boolean blackout) {
        if (!started || me == null) return;
        long now = SystemClock.elapsedRealtime();
        if (ambience != null) {
            float volume = blackout ? .24f : .13f;
            volume += (float)Math.min(.08, me.tension * .08);
            try { ambience.setVolume(volume); } catch (RuntimeException ignored) {}
        }
        if (!me.downed && !me.escaped && moving) {
            long cadence = sprinting ? 275 : 430;
            if (now - lastFootstepMs >= cadence) {
                lastFootstepMs = now;
                play(footstep, sprinting ? .24f : .16f);
            }
        }
        if (!me.downed && !me.escaped && me.tension >= .42) {
            long cadence = Math.max(360, 1050 - Math.round(me.tension * 610));
            if (now - lastHeartbeatMs >= cadence) {
                lastHeartbeatMs = now;
                play(heartbeat, .24f + (float)me.tension * .20f);
            }
        }
    }

    void onEvent(GameEvent.Type type) {
        switch (type) {
            case FUSE_PICKED, KEYCARD_RECOVERED -> play(pickup, .45f);
            case FUSE_INSERTED, BREAKER_ACTIVATED -> play(breaker, .52f);
            case HUNT_SURGE, BLACKOUT_STARTED, PLAYER_DOWNED -> play(danger, .60f);
            case PLAYER_ESCAPED, MATCH_WON -> play(escape, .55f);
            default -> { }
        }
    }

    void release() {
        release(ambience); release(heartbeat); release(footstep); release(pickup);
        release(breaker); release(danger); release(escape);
    }

    private static void play(AudioTrack track, float volume) {
        if (track == null) return;
        try {
            track.pause();
            track.setPlaybackHeadPosition(0);
            track.setVolume(volume);
            track.play();
        } catch (RuntimeException ignored) {}
    }

    private static void release(AudioTrack track) {
        if (track == null) return;
        try { track.release(); } catch (RuntimeException ignored) {}
    }

    private static AudioTrack makeTrack(short[] pcm, boolean loop, int contentType) {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(contentType)
                .build();
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
            AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return null;
            }
            track.write(pcm, 0, pcm.length);
            if (loop) track.setLoopPoints(0, pcm.length, -1);
            return track;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static short[] buildAmbience() {
        int n = SAMPLE_RATE * 6;
        short[] out = new short[n];
        long seed = 0x4e49474854534849L;
        double filtered = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SAMPLE_RATE;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double white = (((seed >>> 40) & 0xffff) / 32768.0) - 1.0;
            filtered = filtered * .985 + white * .015;
            double value = .075 * Math.sin(Math.PI * 2 * 43 * t)
                + .035 * Math.sin(Math.PI * 2 * 86 * t)
                + filtered * .10;
            out[i] = sample(value);
        }
        return out;
    }

    private static short[] buildHeartbeat() {
        int n = (int)(SAMPLE_RATE * 1.15);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double)SAMPLE_RATE;
            double value = thump(t, .07, 60, .72) + thump(t, .29, 53, .48);
            out[i] = sample(value);
        }
        return out;
    }

    private static double thump(double t, double start, double hz, double gain) {
        if (t < start) return 0;
        double x = t - start;
        return gain * Math.sin(Math.PI * 2 * hz * x) * Math.exp(-x * 18.0);
    }

    private static short[] buildFootstep() {
        int n = (int)(SAMPLE_RATE * .20);
        short[] out = new short[n];
        long seed = 0x5348454c4cL;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SAMPLE_RATE;
            seed = seed * 2862933555777941757L + 3037000493L;
            double noise = (((seed >>> 41) & 0x7fff) / 16384.0) - 1.0;
            double value = (.31 * noise + .40 * Math.sin(Math.PI * 2 * 82 * t)) * Math.exp(-t * 22);
            out[i] = sample(value);
        }
        return out;
    }

    private static short[] buildPickup() {
        int n = (int)(SAMPLE_RATE * .30);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double)SAMPLE_RATE;
            double hz = 410 + 920 * t / .30;
            double envelope = Math.min(1, t / .018) * Math.min(1, (.30 - t) / .08);
            out[i] = sample(.26 * Math.sin(Math.PI * 2 * hz * t) * Math.max(0, envelope));
        }
        return out;
    }

    private static short[] buildBreaker() {
        int n = (int)(SAMPLE_RATE * .68);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double)SAMPLE_RATE;
            double hum = (.17 * Math.sin(Math.PI * 2 * 60 * t) + .09 * Math.sin(Math.PI * 2 * 120 * t)) * Math.exp(-t * 2.3);
            double thunk = .34 * Math.sin(Math.PI * 2 * 92 * t) * Math.exp(-Math.pow((t - .075) / .032, 2));
            out[i] = sample(hum + thunk);
        }
        return out;
    }

    private static short[] buildDanger() {
        int n = (int)(SAMPLE_RATE * .82);
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double)SAMPLE_RATE;
            double hz = 145 + 510 * t / .82;
            phase += Math.PI * 2 * hz / SAMPLE_RATE;
            double envelope = Math.min(1, t / .015) * Math.min(1, (.82 - t) / .28);
            out[i] = sample((.22 * Math.sin(phase) + .10 * Math.sin(phase * 2)) * Math.max(0, envelope));
        }
        return out;
    }

    private static short[] buildEscape() {
        int n = (int)(SAMPLE_RATE * .95);
        short[] out = new short[n];
        int[] notes = {330, 440, 550, 660};
        double[] starts = {0, .18, .36, .54};
        for (int i = 0; i < n; i++) {
            double t = i / (double)SAMPLE_RATE;
            double value = 0;
            for (int j = 0; j < notes.length; j++) {
                if (t >= starts[j]) {
                    double x = t - starts[j];
                    value += .12 * Math.sin(Math.PI * 2 * notes[j] * x) * Math.exp(-x * 3.7);
                }
            }
            out[i] = sample(value);
        }
        return out;
    }

    private static short sample(double value) {
        double clamped = Math.max(-1.0, Math.min(1.0, value));
        return (short)Math.round(clamped * 32767.0);
    }
}
