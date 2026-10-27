package com.flandre.netguard.shizuku;

oneway interface INetGuardForegroundListener {
    void onForegroundTask(String task) = 1;
}
