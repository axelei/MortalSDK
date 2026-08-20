package net.krusher.mortalsdk;

import junit.framework.TestCase;

public class TexticleServiceTest extends TestCase {

    public void testCutTextDoesNotOverwriteFollowingData() {
        byte[] rom = new byte[] {9, 9, 9, 9, 9, 9};
        TexticleService.writeCutText(new byte[] {1, 2, 3, 4}, rom, 1, 2);
        assertTrue(java.util.Arrays.equals(new byte[] {9, 1, 2, 9, 9, 9}, rom));
    }
}
