package net.skds.wpo.river;

/** Target-speed controller used by entities so currents do not accelerate forever. */
final class RiverCurrentMotion {

    private RiverCurrentMotion() {
    }

    static double correction(double downstreamSpeed, double targetSpeed, double response, double maximum) {
        if (!(targetSpeed > downstreamSpeed) || !(response > 0.0D) || !(maximum > 0.0D)) {
            return 0.0D;
        }
        return Math.min(maximum, (targetSpeed - downstreamSpeed) * response);
    }
}
