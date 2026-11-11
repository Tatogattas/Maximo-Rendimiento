package com.example;

interface IUserService {
    void destroy() = 16777114;
    void startBenchmarkLoop() = 1;
    void stopBenchmarkLoop() = 2;
    boolean isRunning() = 3;
    String executeCommand(String command) = 4;
}
