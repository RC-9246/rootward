package io.local.rootward.shizuku;

public final class PowerCareReportTest {
  private static void expect(boolean ok) {
    if (!ok) throw new AssertionError();
  }

  public static void main(String[] args) {
    expect(PowerCareReport.current(Integer.MIN_VALUE).equals(PowerCareReport.UNKNOWN));
    expect(PowerCareReport.current(0).equals("0 mA"));
    expect(PowerCareReport.current(-150000).equals("150.00 mA（由电池流出）"));
    expect(PowerCareReport.current(12000).equals("12.00 mA（流入电池）"));
    expect(PowerCareReport.energy(11000000000L).equals("11.00 Wh"));
    expect(PowerCareReport.energy(Long.MIN_VALUE).equals(PowerCareReport.UNKNOWN));
    expect(PowerCareReport.thermalLoad(1.18f).startsWith("1.18"));
    expect(PowerCareReport.thermalLoad(0).startsWith("0.00"));
    expect(PowerCareReport.thermalLoad(Float.NaN).equals(PowerCareReport.UNKNOWN));
    expect(PowerCareReport.thermalStatus(-1).equals(PowerCareReport.UNKNOWN));
    expect(PowerCareReport.thermalStatus(6).contains("关机"));
    expect(PowerCareReport.advice(0, Float.NaN, 2, Integer.MIN_VALUE).isEmpty());
    expect(PowerCareReport.advice(0, 0.4f, 0, -30).isEmpty());
    expect(PowerCareReport.advice(0, Float.NaN, 1, -30).contains("电源和线缆"));
    expect(PowerCareReport.advice(0, 1.1f, 1, -30).contains("散热"));
    expect(PowerCareReport.advice(5, Float.NaN, 0, -30).contains("降温"));
    expect(PowerCareReport.guide("OnePlus", "KB2005", true).contains("LineageOS"));
    expect(PowerCareReport.guide("OnePlus", "KB2005", false).contains("8T"));
    expect(PowerCareReport.guide("Google", "Pixel 5", false).contains("Pixel 6a"));
    expect(!PowerCareReport.guide("Google", "sdk_gphone", false).contains("Pixel"));
    expect(PowerCareReport.guide("SAMSUNG", "SM-F", false).contains("One UI"));
    System.out.println("Power care units, absent values, thermal actions and OEM guidance passed");
  }
}
