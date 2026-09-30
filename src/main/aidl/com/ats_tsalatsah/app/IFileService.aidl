package com.ats_tsalatsah.app;

interface IFileService {
    void destroy() = 16777114;

    String readLatestLog(String pkg) = 1;

    boolean forceStop(String pkg) = 2;
}
