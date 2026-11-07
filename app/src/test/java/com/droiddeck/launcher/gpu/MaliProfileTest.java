package com.droiddeck.launcher.gpu;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class MaliProfileTest {
    @Test public void parsesMaliAndImmortalisGpuNames() {
        assertEquals("Mali-G615 MC6", MaliProfile.name("Mali-G615 MC6"));
        assertEquals(615, MaliProfile.model("Mali-G615 6 cores r1p3"));
        assertEquals(720, MaliProfile.model("Immortalis-G720 MC12"));
    }

    @Test public void ignoresSocNamesAndOtherGpuFamilies() {
        assertEquals("", MaliProfile.name("MediaTek Dimensity 8350-Ultimate"));
        assertEquals(0, MaliProfile.model(null));
        assertEquals(0, MaliProfile.model("Adreno 615"));
    }
}