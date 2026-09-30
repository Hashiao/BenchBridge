package io.benchbridge.app.storage;
interface IStorageProbe {
    int pid();
    String inspect(String directory, boolean aio);
}
