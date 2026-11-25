package com.opspilot.demo.metrics;

/**
 * 同线程请求上下文：service 层写入真实 DB/下游耗时，流量入口读取后记录指标。
 */
public final class RequestContext {

    private static final ThreadLocal<double[]> HOLDER = new ThreadLocal<>();

    private RequestContext() {}

    public static void record(double dbRtMs, double downstreamRtMs) {
        HOLDER.set(new double[]{dbRtMs, downstreamRtMs});
    }

    /** 返回 [dbRtMs, downstreamRtMs]，无记录时返回 [0, 0] */
    public static double[] current() {
        double[] v = HOLDER.get();
        return v == null ? new double[]{0, 0} : v;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
