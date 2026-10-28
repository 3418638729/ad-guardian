package com.ycl.adguardian;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 去广告链路的体检。
 *
 * 一条完整的拦截链其实由两段独立的机制组成：
 *
 *   1) AdAway：VPN 接管系统 DNS → 广告域名解析失败 → 广告素材加载不出来
 *   2) GKD   ：无障碍读屏 → 找到"跳过"按钮 → 自动点掉开屏广告
 *
 * 任何一段断了，用户看到的都是"还有广告"，但原因完全不同。
 * 所以这里分段检测，报告里也分开说，修的时候才不会开错药。
 *
 * 最后一条检测会真的去解析广告域名——这是唯一能证明
 * "拦截确实在工作"的判据，比看进程、看界面都可靠。
 */
public final class Checks {

    public static final String PKG_ADAWAY = "org.adaway";
    public static final String PKG_GKD = "li.songe.gkd";

    /** 广告 SDK 的代表性域名，全部应在黑名单里。 */
    public static final String[] TEST_DOMAINS = {
            "gdt.qq.com",          // 优量汇
            "pgdt.gtimg.cn",       // 腾讯广告素材
            "ad.oceanengine.com",  // 穿山甲/巨量
            "alimama.com",         // 阿里妈妈
    };

    /** 对照组：这个域名必须能解析，否则说明是断网而不是拦截成功。 */
    private static final String CONTROL_DOMAIN = "www.baidu.com";

    public enum State { OK, PROBLEM, INFO }

    /** 点"处理"时该把用户送到哪。 */
    public enum Action { NONE, OPEN_A11Y, OPEN_ADAWAY }

    public static final class Item {
        public final String title;
        public final State state;
        public final String detail;
        public final Action action;

        Item(String title, State state, String detail, Action action) {
            this.title = title;
            this.state = state;
            this.detail = detail;
            this.action = action;
        }
    }

    public static final class Report {
        public final List<Item> items = new ArrayList<>();

        public boolean allOk() {
            for (Item i : items) {
                if (i.state == State.PROBLEM) return false;
            }
            return true;
        }

        public int problemCount() {
            int n = 0;
            for (Item i : items) {
                if (i.state == State.PROBLEM) n++;
            }
            return n;
        }

        /** 按报告顺序返回第一个需要处理的问题。 */
        public Item firstProblem() {
            for (Item i : items) {
                if (i.state == State.PROBLEM) return i;
            }
            return null;
        }
    }

    private Checks() {
    }

    /** 跑一遍全部检测。会发起网络请求，必须在子线程调用。 */
    public static Report run(Context ctx) {
        Context app = ctx.getApplicationContext();
        Report r = new Report();

        // ---- 第一段：GKD 无障碍 ----
        boolean gkdInstalled = isInstalled(app, PKG_GKD);
        if (gkdInstalled) {
            r.items.add(new Item("GKD 已安装", State.OK, "", Action.NONE));

            if (a11yEnabled(app)) {
                r.items.add(new Item("GKD 无障碍已开启", State.OK, "", Action.NONE));
            } else {
                r.items.add(new Item("GKD 无障碍未开启", State.PROBLEM,
                        "系统把无障碍关了，GKD 就没法读屏、点不了跳过", Action.OPEN_A11Y));
            }
        } else {
            r.items.add(new Item("GKD 未安装", State.PROBLEM,
                    "自动跳过广告要靠它，需要先装一个 GKD", Action.NONE));
        }

        // ---- 第二段：AdAway DNS 拦截 ----
        boolean adawayInstalled = isInstalled(app, PKG_ADAWAY);
        if (adawayInstalled) {
            r.items.add(new Item("AdAway 已安装", State.OK, "", Action.NONE));

            if (vpnOn(app)) {
                r.items.add(new Item("DNS 拦截已连接", State.OK, "", Action.NONE));
            } else {
                r.items.add(new Item("DNS 拦截未连接", State.PROBLEM,
                        "AdAway 的 VPN 没在跑，打开它点一下启动", Action.OPEN_ADAWAY));
            }
        } else {
            r.items.add(new Item("AdAway 未安装", State.PROBLEM,
                    "DNS 拦截要靠它，需要先装一个 AdAway", Action.NONE));
        }

        // ---- 终审：真的去解析广告域名 ----
        Probe probe = probeAds();
        switch (probe.result) {
            case OK:
                r.items.add(new Item("广告域名已拦截 "
                        + probe.blocked + "/" + TEST_DOMAINS.length,
                        State.OK, "", Action.NONE));
                break;
            case NOT_BLOCKED:
                r.items.add(new Item("广告域名没被拦（"
                        + probe.blocked + "/" + TEST_DOMAINS.length + " 生效）",
                        State.PROBLEM,
                        "域名能解析到真实地址，说明规则没生效，去 AdAway 重新应用",
                        Action.OPEN_ADAWAY));
                break;
            case NO_NETWORK:
            default:
                r.items.add(new Item("网络不可用，无法判断拦截效果",
                        State.INFO, "连上网络后再检测一次", Action.NONE));
                break;
        }

        return r;
    }

    // ---------- 单项检测 ----------

    private static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** 无障碍总开关 + GKD 的服务是否在启用列表里，两者缺一不可。 */
    private static boolean a11yEnabled(Context ctx) {
        int enabled = Settings.Secure.getInt(ctx.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        if (enabled != 1) return false;

        String services = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null && services.contains(PKG_GKD);
    }

    private static boolean vpnOn(Context ctx) {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    private enum ProbeResult { OK, NOT_BLOCKED, NO_NETWORK }

    private static final class Probe {
        ProbeResult result = ProbeResult.NO_NETWORK;
        int blocked = 0;
    }

    private static Probe probeAds() {
        Probe p = new Probe();

        // 先确认网络本身通不通，否则全部解析失败会被误判成"全拦住了"
        if (!resolves(CONTROL_DOMAIN)) {
            p.result = ProbeResult.NO_NETWORK;
            return p;
        }

        int blocked = 0;
        for (String domain : TEST_DOMAINS) {
            if (!resolves(domain)) blocked++;
        }
        p.blocked = blocked;
        p.result = blocked == TEST_DOMAINS.length
                ? ProbeResult.OK
                : ProbeResult.NOT_BLOCKED;
        return p;
    }

    private static boolean resolves(String host) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            return all != null && all.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
