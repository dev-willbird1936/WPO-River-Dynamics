package net.skds.wpo.river;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

final class ChannelTangentTest {

    @Test
    void followsAQuarterTurnFromTheStraightSection() {
        List<ChannelTangent.Sample> samples = new ArrayList<>();
        for (int x = -12; x <= 0; x++) {
            for (int z = -1; z <= 1; z++) {
                samples.add(new ChannelTangent.Sample(x, z, 70 - x / 8));
            }
        }
        for (int z = 0; z <= 12; z++) {
            for (int x = -1; x <= 1; x++) {
                samples.add(new ChannelTangent.Sample(x, z, 70 - z / 8));
            }
        }

        ChannelTangent.Axis axis = ChannelTangent.resolve(-8, 0, 71, 1.0D, 0.0D, samples).orElseThrow();

        assertTrue(axis.x() > 0.9D, "the straight reach must remain eastward");
        assertTrue(Math.abs(axis.z()) < 0.25D);
    }

    @Test
    void followsTheOtherLegAfterTheTurn() {
        List<ChannelTangent.Sample> samples = new ArrayList<>();
        for (int x = -12; x <= 0; x++) {
            for (int z = -1; z <= 1; z++) {
                samples.add(new ChannelTangent.Sample(x, z, 70));
            }
        }
        for (int z = 0; z <= 12; z++) {
            for (int x = -1; x <= 1; x++) {
                samples.add(new ChannelTangent.Sample(x, z, 70 - z / 8));
            }
        }

        ChannelTangent.Axis axis = ChannelTangent.resolve(0, 8, 69, 0.0D, 1.0D, samples).orElseThrow();

        assertTrue(axis.z() > 0.9D, "the downstream leg must turn southward");
        assertTrue(Math.abs(axis.x()) < 0.25D);
    }

    @Test
    void branchChoosesTheLowerLegWhenTheRawBearingIsAmbiguous() {
        List<ChannelTangent.Sample> samples = new ArrayList<>();
        for (int x = -10; x <= 10; x++) {
            samples.add(new ChannelTangent.Sample(x, 0, 70 - Math.max(0, x) / 6));
        }
        for (int z = 0; z <= 10; z++) {
            samples.add(new ChannelTangent.Sample(0, z, 70 - z / 3));
        }

        ChannelTangent.Axis axis = ChannelTangent.resolve(0, 4, 68, 0.0D, 0.0D, samples).orElseThrow();

        assertTrue(axis.z() > 0.8D, "the lower branch should win at a junction");
    }
}
