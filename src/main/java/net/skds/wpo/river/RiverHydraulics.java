package net.skds.wpo.river;

/** Converts channel geometry into one gameplay-scale physical current speed. */
final class RiverHydraulics {

    static final double MIN_SPEED = 0.018D;
    static final double MAX_SPEED = 0.180D;

    private static final double MANNING_ROUGHNESS = 0.045D;
    private static final double TICKS_PER_SECOND = 20.0D;
    private static final double MIN_EFFECTIVE_SLOPE = 0.00035D;

    private RiverHydraulics() {
    }

    static double speed(double widthBlocks, double depthBlocks, double bedSlope) {
        double width = clamp(widthBlocks, 1.0D, 64.0D);
        double depth = clamp(depthBlocks, 0.5D, 16.0D);
        double wettedPerimeter = width + (2.0D * depth);
        double hydraulicRadius = (width * depth) / wettedPerimeter;
        double slope = clamp(Math.max(MIN_EFFECTIVE_SLOPE, bedSlope), MIN_EFFECTIVE_SLOPE, 0.25D);
        double metersPerSecond = Math.pow(hydraulicRadius, 2.0D / 3.0D)
                * Math.sqrt(slope)
                / MANNING_ROUGHNESS;
        return clamp(metersPerSecond / TICKS_PER_SECOND, MIN_SPEED, MAX_SPEED);
    }

    static double biasStrength(double speed, double minimum, double maximum) {
        if (!(maximum > minimum)) {
            return minimum;
        }
        double normalized = clamp((speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED), 0.0D, 1.0D);
        return minimum + (Math.sqrt(normalized) * (maximum - minimum));
    }

    static double speedFromBiasStrength(double strength, double minimum, double maximum) {
        if (!(maximum > minimum)) {
            return MIN_SPEED;
        }
        double normalized = clamp((strength - minimum) / (maximum - minimum), 0.0D, 1.0D);
        return MIN_SPEED + (normalized * normalized * (MAX_SPEED - MIN_SPEED));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
