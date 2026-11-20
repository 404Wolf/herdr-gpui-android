package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class CameraResolutionTest {
    private static final String PIXEL = "com.google.android.GoogleCamera";
    private static final String GRAPHENE = "app.grapheneos.camera";
    private static final String APERTURE = "org.lineageos.aperture";

    private static final class Cameras {
        final List<String> targetedQueries = new ArrayList<>();
        final List<String> implicit;
        final List<String> targeted;

        Cameras(List<String> implicit, String... targeted) {
            this.implicit = implicit;
            this.targeted = Arrays.asList(targeted);
        }

        String resolve() {
            return AttachImages.resolveCamera("capture", implicit.size(), intent -> "chooser for " + intent,
                    name -> {
                        targetedQueries.add(name);
                        return targeted.contains(name) ? name : null;
                    });
        }
    }

    @Test public void aSingleImplicitMatchLaunchesNormally() {
        Cameras cameras = new Cameras(Collections.singletonList("system.camera"), PIXEL);
        assertEquals("capture", cameras.resolve());
        assertTrue(cameras.targetedQueries.isEmpty());
    }

    @Test public void severalImplicitMatchesOpenTheChooser() {
        Cameras cameras = new Cameras(Arrays.asList("system.camera.one", "system.camera.two"), PIXEL);
        assertEquals("chooser for capture", cameras.resolve());
        assertTrue(cameras.targetedQueries.isEmpty());
    }

    @Test public void noImplicitMatchTriesPixelFirst() {
        Cameras cameras = new Cameras(Collections.emptyList(), PIXEL, GRAPHENE, APERTURE);
        assertEquals(PIXEL, cameras.resolve());
        assertEquals(Collections.singletonList(PIXEL), cameras.targetedQueries);
    }

    @Test public void grapheneCameraTakesPrecedenceOverAperture() {
        Cameras cameras = new Cameras(Collections.emptyList(), GRAPHENE, APERTURE);
        assertEquals(GRAPHENE, cameras.resolve());
        assertEquals(Arrays.asList(PIXEL, GRAPHENE), cameras.targetedQueries);
    }

    @Test public void apertureIsTheLastFallback() {
        Cameras cameras = new Cameras(Collections.emptyList(), APERTURE);
        assertEquals(APERTURE, cameras.resolve());
        assertEquals(Arrays.asList(PIXEL, GRAPHENE, APERTURE), cameras.targetedQueries);
    }

    @Test public void noMatchingCameraReturnsNoIntent() {
        Cameras cameras = new Cameras(Collections.emptyList());
        assertNull(cameras.resolve());
        assertEquals(Arrays.asList(PIXEL, GRAPHENE, APERTURE), cameras.targetedQueries);
    }
}
