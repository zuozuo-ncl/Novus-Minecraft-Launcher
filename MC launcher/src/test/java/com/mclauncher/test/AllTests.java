package com.mclauncher.test;

/** 全部离线测试的入口。 */
public final class AllTests {

    public static void main(String[] args) throws Exception {
        RelayEndpointsTest.main(args);
        System.out.println();
        ArchRuleTest.main(args);
        System.out.println();
        PeerForwardTopologyTest.main(args);
        System.out.println();
        int code = Checks.summarize();
        System.exit(code);
    }

    private AllTests() {}
}