package net.skds.wpo.river;

import java.util.Optional;

final class FlowConsensus {

    private static final double MIN_SHAPE_ANISOTROPY = 1.35D;
    private static final double MIN_VECTOR_COHERENCE = 0.15D;
    private static final double MIN_BED_SLOPE = 0.005D;
    private static final double MIN_BED_GRADIENT = 0.01D;

    private FlowConsensus() {
    }

    static Neighborhood neighborhood(double centerX, double centerZ, double radius) {
        return new Neighborhood(centerX, centerZ, radius);
    }

    static Optional<Axis> signedReferenceAxis(double x, double z) {
        double length = Math.hypot(x, z);
        return length > 1.0E-6D
                ? Optional.of(new Axis(x / length, z / length))
                : Optional.empty();
    }

    static final class Neighborhood {
        private final double centerX;
        private final double centerZ;
        private final double radiusSquared;
        private final Summary summary = new Summary();

        private Neighborhood(double centerX, double centerZ, double radius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radiusSquared = radius * radius;
        }

        void add(double x, double z, double bedHeight, double rawX, double rawZ, double strength) {
            double dx = x - centerX;
            double dz = z - centerZ;
            if ((dx * dx) + (dz * dz) <= radiusSquared) {
                summary.add(x, z, bedHeight, rawX, rawZ, strength);
            }
        }

        Optional<Axis> resolve(long minimumSamples) {
            return summary.count >= minimumSamples ? summary.resolve() : Optional.empty();
        }
    }

    static final class Summary {
        private long count;
        private double meanX;
        private double meanZ;
        private double meanBed;
        private double varianceX;
        private double varianceZ;
        private double covarianceXZ;
        private double covarianceXBed;
        private double covarianceZBed;
        private double vectorX;
        private double vectorZ;
        private double axisCos2;
        private double axisSin2;
        private double vectorWeight;

        void add(double x, double z, double bedHeight, double rawX, double rawZ, double strength) {
            long nextCount = count + 1L;
            double dx = x - meanX;
            double dz = z - meanZ;
            double db = bedHeight - meanBed;
            meanX += dx / nextCount;
            meanZ += dz / nextCount;
            meanBed += db / nextCount;
            varianceX += dx * (x - meanX);
            varianceZ += dz * (z - meanZ);
            covarianceXZ += dx * (z - meanZ);
            covarianceXBed += dx * (bedHeight - meanBed);
            covarianceZBed += dz * (bedHeight - meanBed);
            count = nextCount;

            double length = Math.sqrt((rawX * rawX) + (rawZ * rawZ));
            double weight = Math.max(0.0D, strength);
            if (length > 1.0E-6D && weight > 0.0D) {
                double xUnit = rawX / length;
                double zUnit = rawZ / length;
                vectorX += xUnit * weight;
                vectorZ += zUnit * weight;
                axisCos2 += ((xUnit * xUnit) - (zUnit * zUnit)) * weight;
                axisSin2 += (2.0D * xUnit * zUnit) * weight;
                vectorWeight += weight;
            }
        }

        void merge(Summary other) {
            if (other.count == 0L) {
                return;
            }
            if (count == 0L) {
                copyFrom(other);
                return;
            }

            long combinedCount = count + other.count;
            double factor = (double) count * other.count / combinedCount;
            double dx = other.meanX - meanX;
            double dz = other.meanZ - meanZ;
            double db = other.meanBed - meanBed;
            varianceX += other.varianceX + (dx * dx * factor);
            varianceZ += other.varianceZ + (dz * dz * factor);
            covarianceXZ += other.covarianceXZ + (dx * dz * factor);
            covarianceXBed += other.covarianceXBed + (dx * db * factor);
            covarianceZBed += other.covarianceZBed + (dz * db * factor);
            meanX += dx * other.count / combinedCount;
            meanZ += dz * other.count / combinedCount;
            meanBed += db * other.count / combinedCount;
            count = combinedCount;
            vectorX += other.vectorX;
            vectorZ += other.vectorZ;
            axisCos2 += other.axisCos2;
            axisSin2 += other.axisSin2;
            vectorWeight += other.vectorWeight;
        }

        Optional<Axis> resolve() {
            if (count == 0L || vectorWeight <= 0.0D) {
                return Optional.empty();
            }

            // A short neighborhood can contain a bend whose point cloud is still wide or
            // nearly round. In that case its streambed gradient is the local tangent: water
            // runs downhill, including diagonally, instead of inheriting a reach-wide axis.
            double gradientX = varianceX > 1.0D ? covarianceXBed / varianceX : 0.0D;
            double gradientZ = varianceZ > 1.0D ? covarianceZBed / varianceZ : 0.0D;
            double downhillX = -gradientX;
            double downhillZ = -gradientZ;
            double downhillLength = Math.sqrt((downhillX * downhillX) + (downhillZ * downhillZ));
            if (count >= 4L && downhillLength >= MIN_BED_GRADIENT) {
                return Optional.of(new Axis(downhillX / downhillLength, downhillZ / downhillLength));
            }

            double axisX;
            double axisZ;
            double trace = varianceX + varianceZ;
            double difference = varianceX - varianceZ;
            double discriminant = Math.sqrt((difference * difference) + (4.0D * covarianceXZ * covarianceXZ));
            double major = (trace + discriminant) * 0.5D;
            double minor = Math.max(0.0D, (trace - discriminant) * 0.5D);
            if (count >= 4L && major > 1.0D && major >= minor * MIN_SHAPE_ANISOTROPY) {
                double angle = 0.5D * Math.atan2(2.0D * covarianceXZ, difference);
                axisX = Math.cos(angle);
                axisZ = Math.sin(angle);
            } else {
                double coherence = Math.sqrt((axisCos2 * axisCos2) + (axisSin2 * axisSin2));
                if (coherence < vectorWeight * MIN_VECTOR_COHERENCE) {
                    double signedLength = Math.sqrt((vectorX * vectorX) + (vectorZ * vectorZ));
                    if (signedLength < 1.0E-6D) {
                        return Optional.empty();
                    }
                    return Optional.of(new Axis(vectorX / signedLength, vectorZ / signedLength));
                }
                double angle = 0.5D * Math.atan2(axisSin2, axisCos2);
                axisX = Math.cos(angle);
                axisZ = Math.sin(angle);
            }

            double bedCovariance = (axisX * covarianceXBed) + (axisZ * covarianceZBed);
            double axisVariance = (axisX * axisX * varianceX)
                    + (2.0D * axisX * axisZ * covarianceXZ)
                    + (axisZ * axisZ * varianceZ);
            double bedSlope = axisVariance > 1.0D ? bedCovariance / axisVariance : 0.0D;
            if (Math.abs(bedSlope) >= MIN_BED_SLOPE) {
                if (bedSlope > 0.0D) {
                    axisX = -axisX;
                    axisZ = -axisZ;
                }
            } else {
                double signedProjection = (axisX * vectorX) + (axisZ * vectorZ);
                if (Math.abs(signedProjection) >= vectorWeight * MIN_VECTOR_COHERENCE) {
                    if (signedProjection < 0.0D) {
                        axisX = -axisX;
                        axisZ = -axisZ;
                    }
                } else if ((Math.abs(axisX) >= Math.abs(axisZ) && axisX < 0.0D)
                        || (Math.abs(axisZ) > Math.abs(axisX) && axisZ < 0.0D)) {
                    axisX = -axisX;
                    axisZ = -axisZ;
                }
            }
            return Optional.of(new Axis(axisX, axisZ));
        }

        private void copyFrom(Summary other) {
            count = other.count;
            meanX = other.meanX;
            meanZ = other.meanZ;
            meanBed = other.meanBed;
            varianceX = other.varianceX;
            varianceZ = other.varianceZ;
            covarianceXZ = other.covarianceXZ;
            covarianceXBed = other.covarianceXBed;
            covarianceZBed = other.covarianceZBed;
            vectorX = other.vectorX;
            vectorZ = other.vectorZ;
            axisCos2 = other.axisCos2;
            axisSin2 = other.axisSin2;
            vectorWeight = other.vectorWeight;
        }
    }

    record Axis(double x, double z) {
    }
}
