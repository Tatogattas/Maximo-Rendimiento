package com.example;

interface IUserService {
    void destroy() = 16777114;
    void startPersistentTask() = 1;
    void stopPersistentTask() = 2;
    boolean isTaskRunning() = 3;
    String getTaskStatus() = 4;
    String executeCommand(String command) = 5;
    long getExecutionCount() = 6;
}
