package io.local.rootward.shizuku;
import android.os.Bundle;

interface IBatteryReader {
    Bundle nativeLimit(String expectedId, int beforeStart, int beforeEnd, int side, int value, long expires) = 3;
    void destroy() = 16777114;
    String readSnapshot() = 1;
    Bundle chargeMode(String expectedId, int action) = 4;
}
