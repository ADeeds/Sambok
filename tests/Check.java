/** Minimal assertion helper for the host tests; no JUnit on this machine. */
final class Check {

    private static int passed;
    private static int failed;

    private Check() {
    }

    static void t(String name, Object actual, Object expected) {
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }

    /** Prints the tally and exits non-zero if anything failed. */
    static void done(String suite) {
        System.out.println(suite + ": " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            System.exit(1);
        }
    }
}
