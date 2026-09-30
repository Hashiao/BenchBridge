package io.benchbridge.app.ram;

interface IRamRunner {
    String startCompute(String configJson);
    String computeCapabilities();
    String startStorage(String configJson);
    String storageCapabilities();
    String cleanupStorage(String runId);
    String startRam(String configJson);
    String snapshot(String runId);
    void cancel(String runId, String reason);
    String capabilities();
}
