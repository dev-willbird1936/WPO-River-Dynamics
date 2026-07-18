package net.skds.wpo.river;

import java.util.Collection;
import java.util.Optional;

/** Resolves a short, signed tangent from the locally connected surface-water samples. */
final class ChannelTangent {

    private static final int MAX_RUN = 12;
    private static final double SAMPLE_CORRIDOR = 2.25D;
    private static final int MIN_RUN_SCORE = 4;
    private static final int MIN_SCORE_MARGIN = 2;

    private static final int[][] AXES = {
            { 1, 0 },
            { 1, 1 },
            { 0, 1 },
            { -1, 1 }
    };

    private ChannelTangent() {
    }

    static Optional<Axis> resolve(
            int centerX,
            int centerZ,
            double centerBed,
            double rawX,
            double rawZ,
            Collection<Sample> samples
    ) {
        Candidate best = null;
        Candidate second = null;
        for (int[] axis : AXES) {
            Candidate candidate = candidate(centerX, centerZ, centerBed, axis[0], axis[1], samples);
            if (candidate.score < MIN_RUN_SCORE) {
                continue;
            }
            if (best == null || better(candidate, best)) {
                second = best;
                best = candidate;
            } else if (second == null || better(candidate, second)) {
                second = candidate;
            }
        }
        if (best == null) {
            return Optional.empty();
        }

        int scoreMargin = second == null ? best.score : best.score - second.score;
        if (scoreMargin < MIN_SCORE_MARGIN && Math.abs(best.dropSignal) < 0.75D) {
            return Optional.empty();
        }

        double sign = sign(best, rawX, rawZ);
        double length = Math.sqrt((double) (best.axisX * best.axisX + best.axisZ * best.axisZ));
        return Optional.of(new Axis(sign * best.axisX / length, sign * best.axisZ / length));
    }

    private static boolean better(Candidate candidate, Candidate incumbent) {
        if (candidate.score != incumbent.score) {
            return candidate.score > incumbent.score;
        }
        return Math.abs(candidate.dropSignal) > Math.abs(incumbent.dropSignal);
    }

    private static Candidate candidate(
            int centerX,
            int centerZ,
            double centerBed,
            int axisX,
            int axisZ,
            Collection<Sample> samples
    ) {
        Run positive = run(centerX, centerZ, centerBed, axisX, axisZ, 1, samples);
        Run negative = run(centerX, centerZ, centerBed, axisX, axisZ, -1, samples);
        return new Candidate(
                axisX,
                axisZ,
                positive.run + negative.run,
                positive.drop - negative.drop
        );
    }

    private static Run run(
            int centerX,
            int centerZ,
            double centerBed,
            int axisX,
            int axisZ,
            int sign,
            Collection<Sample> samples
    ) {
        int count = 0;
        double drop = 0.0D;
        for (int step = 1; step <= MAX_RUN; step++) {
            double expectedX = centerX + (axisX * sign * step);
            double expectedZ = centerZ + (axisZ * sign * step);
            Sample sample = nearest(expectedX, expectedZ, axisX * sign, axisZ * sign, step, samples);
            if (sample == null) {
                break;
            }
            count++;
            drop += centerBed - sample.bedHeight;
        }
        return new Run(count, drop);
    }

    private static Sample nearest(
            double expectedX,
            double expectedZ,
            int directionX,
            int directionZ,
            int step,
            Collection<Sample> samples
    ) {
        double axisLength = Math.sqrt((double) (directionX * directionX + directionZ * directionZ));
        Sample best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Sample sample : samples) {
            double dx = sample.x - expectedX;
            double dz = sample.z - expectedZ;
            double along = (dx * directionX + dz * directionZ) / axisLength;
            double perpendicular = Math.abs((dx * directionZ - dz * directionX) / axisLength);
            if (Math.abs(along) > 1.35D || perpendicular > SAMPLE_CORRIDOR) {
                continue;
            }
            double distance = (along * along) + (perpendicular * perpendicular);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = sample;
            }
        }
        return best;
    }

    private static double sign(Candidate candidate, double rawX, double rawZ) {
        if (candidate.dropSignal > 0.5D) {
            return 1.0D;
        }
        if (candidate.dropSignal < -0.5D) {
            return -1.0D;
        }
        double projection = rawX * candidate.axisX + rawZ * candidate.axisZ;
        if (Math.abs(projection) > 0.05D) {
            return projection < 0.0D ? -1.0D : 1.0D;
        }
        return 1.0D;
    }

    record Sample(int x, int z, double bedHeight) {
    }

    record Axis(double x, double z) {
    }

    private record Run(int run, double drop) {
    }

    private record Candidate(int axisX, int axisZ, int score, double dropSignal) {
    }
}
