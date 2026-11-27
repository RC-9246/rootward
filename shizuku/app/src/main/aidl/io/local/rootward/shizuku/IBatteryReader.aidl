package io.local.rootward.shizuku;
interface IBatteryReader {
    void destroy() = 16777114;
    String readSnapshot() = 1;
    int chargeBehaviour(int action) = 2;
}
