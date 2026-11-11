package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AllAnimeBundleParserTest {
    @Test
    fun parsesBuildAndUnambiguouslyRotatedSeeds() {
        val js = """
            function table(){const values=['AQIDBA','UGBwg=','ERITFA','UGB4g=','ISIjJC','UmJyg=','MTIzND','U2Nzg=','junk']}
            function decode(index){return index=index-(0),table()[index]}
            const seeds=[decode(0)+decode(1),decode(2)+decode(3),decode(4)+decode(5),decode(6)+decode(7)];
            const build=value!=="string"?"75":"";
        """.trimIndent()

        val parsed = AllAnimeBundleParser.parse(js)

        assertNotNull(parsed)
        assertEquals("75", parsed?.buildId)
        assertEquals(listOf("AQIDBAUGBwg=", "ERITFAUGB4g=", "ISIjJCUmJyg=", "MTIzNDU2Nzg="), parsed?.seeds)
    }

    /**
     * The fixture below is a verbatim slice of MKissa's live crypto chunk (2026-09-13): its string
     * table, the dollar-named base decoder, the `At`/`fr` aliases in front of it, the build-id getter
     * and the seed array. Nothing is normalised, because the three things that broke the parser are
     * exactly the things a tidied fixture would lose — the dollar in the `$r` base decoder, which
     * `\w` does not match,
     * the `{_0x453117:564}._0x453117` written where a plain 564 would do, and seeds built from four
     * table reads where the bundle above uses two.
     */
    @Test
    fun `build id and seeds come off the same rotation of the live table`() {
        val parsed = AllAnimeBundleParser.parse(LIVE_CHUNK_SLICE)

        assertEquals("168", parsed?.buildId)
        assertEquals(
            listOf("Xe0sbWji894=", "kDl8ZYLJjcY=", "Zp9QHHse7BY=", "UscVFCx+xrI="),
            parsed?.seeds,
        )
    }

    @Test
    fun `a bundle whose base decoder is unreachable yields nothing rather than a guess`() {
        // Deriving a key from a half-read table produces a request that looks authentic and is not,
        // so the parser must fail instead. Renaming the table breaks every alias pointing at it.
        val broken = LIVE_CHUNK_SLICE.replace("Yc()[e]", "Zz()[e]")

        assertNull(AllAnimeBundleParser.parse(broken))
    }

    @Test
    fun `an inlined build id still wins, so a bundle that goes back keeps working`() {
        val withLiteral = LIVE_CHUNK_SLICE + ";var x=typeof q!==\"string\"?\"4242\":\"\";"

        assertEquals("4242", AllAnimeBundleParser.parse(withLiteral)?.buildId)
    }

    /**
     * Build 175 (live 2026-09-22) broke the parser three ways at once, and a verbatim slice is the
     * only fixture that keeps all three: the build-id getter now hides its `||` behind a
     * string-table property name, so the id is recovered from the seed array's own statement; the
     * table reads take two arguments (`Dt(-460,-418)`), so a plain comma split cut every seed in
     * half; and the mask/boot-token constants moved into a `{saltMul:…}` config object.
     *
     * Every expected value was produced by Anivexa's own evaluator run against the same live chunk,
     * and its bootstrap request with them answered 200.
     */
    @Test
    fun `build 175 reads its build id from the seed statement and its scheme from the config`() {
        val parsed = AllAnimeBundleParser.parse(BUILD_175_SLICE)

        assertEquals("175", parsed?.buildId)
        assertEquals(listOf("jfTMXeWz1KY=", "sSiL9IX1c1k=", "jjOcW/T0DYU=", "vRF6mguezW8="), parsed?.seeds)
        assertEquals(
            AllAnimeMkissaCrypto.Scheme(
                saltMul = 34,
                saltAdd = 47,
                fragMul = 93,
                fragAdd = 29,
                bootPrefix = "X8S061oCq:",
                join = "|",
                parts = listOf("buildId", "group", "host", "epoch", "lane"),
                omitEmptyLane = false,
            ),
            parsed?.scheme,
        )
    }

    @Test
    fun `build 175 signs the same boot token the live bootstrap accepted`() {
        val parsed = AllAnimeBundleParser.parse(BUILD_175_SLICE)!!
        val mask = AllAnimeMkissaCrypto.deriveMask(parsed.buildId, parsed.seeds, parsed.scheme)!!

        assertEquals(
            "938fb0ae11ceb047e602a8d0a2c5466d4ca0cac838774908c493bd00500a12f7",
            mask.joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "5d9119460a8aa7be138a513c71e6597f759f18a2783514f793e836ca81b13d85",
            AllAnimeMkissaCrypto.bootToken(
                mask = mask,
                buildId = parsed.buildId,
                epoch = 2959,
                keyGroup = "mkissa",
                refererHost = "mkissa.to",
                lane = "k7",
                scheme = parsed.scheme,
            ),
        )
    }

    @Test
    fun `a config that is present but unreadable fails instead of signing with the old constants`() {
        val broken = BUILD_175_SLICE.replace("fragMul:93", "fragMul:x93")

        assertNull(AllAnimeBundleParser.parse(broken))
    }

    @Test
    fun `a bundle without the config keeps the legacy scheme`() {
        assertEquals(AllAnimeMkissaCrypto.Scheme.LEGACY, AllAnimeBundleParser.parse(LIVE_CHUNK_SLICE)?.scheme)
    }

    private companion object {
        val LIVE_CHUNK_SLICE = """
            function Yc(){const e=["597076YzFCvN","xdn","Lan","tEm","YKj","ect","FEP","sKe","Ihd","nDa","CoV","joi","tab","Fek","tot","-25","tra","war","alh","ADR","ode","ZPF","tLu","mLK","BY=","zSL","Zp9","hos","XIf","sub","SXc","48BEdKrf","tPr","ZVO",".0.","414rhRlHh","NZj","SHA","tna","dxI","PyZ","unc","EJW","jmb","xEb","tio","fod","ONU","hNz","HMA","EOM","uQN","twu","isA","11936260XoHgVz","FXS","toL","KYU","se7","sin","lan","mev","168","mis","inf","wqz","ori","Usc","Wtp","sig","CwA","94=","Ntb","g_m","bui","cY=","Xor","boo","ETr","n t","vYi","Zor","Mjf","ion","gth","kTv","drM","iFC","ask","2764oOwDye","Bmp","127","xkE","JGE","ept","VFC","his","yaG","sal","pto","iCX","IzX","rCo","rI=","QUe","rep","LxM","NlA","pro","UCS","nDC","iXF","mCh","Zke","Id=","XkY","dUE","rCa","koe","PIe","ail",'"re',"tAd","n()","Sta","170FEdwWs","JIY","imp","Cjv","ing","toS","pwF","Key","jAF","ret","leg","tle","64suKBcY","x+x","iyw","493273HuRwzW","IyO","xsI","fra","gro","NKN","fRV","tMu","gin","owe","fro","?bu","SJK","acy","lac","str","EET","deA","kbU","NLg","efi","SJg","asT","sbW","Ubj","tur","obj","gyN","RHF","ype","bin","nxf","log","abl","gKh","exc","CbI","bRp","loc","con","YIY","urn","{}.","RCL","uKg","gMu","rot","WYr","tXX","ild","cry","env","12745ALRHfl","Vfc","Yqq","BMm","arC","raw",'")(',"8ZY","Vdb","nav","Krw","o__","sYF","ort","kDl","nKn","xVK","sYB","QHH","Vvy","MmG","enc"," (f","0.1","wEo","len","wCL","epo","Tth","vwp","vmc","o_u","Sxt","4775535GmuQrN","tri","aa-","kNS","Uuy","pty","xJo","or(","cwS","sol","3Dm","ypt","__p","Xe0","ost","pad","3jGSmcs","BFb","xBv","&k=","nIY","omi","zSl","AZR","Aeu","992845eeEUGT","JfL","web","gAd","Diz","CPw","par","app","LJj","lcg","cha","hbq","ji8","_cr","rHG","jIV","rra","map","uct","ldI","err"];return Yc=function(){return e}
            function ${'$'}r(e,t){return e=e-340,Yc()[e]}
            function At(e,t){return ${'$'}r(e-{_0x453117:564}._0x453117)}
            function fr(e,t){return ${'$'}r(e-377)}
            function uy(){return sd||""}
            const sd=fr(945);
            const dm=[At(1037)+At(962)+At(1061)+At(1141),At(1005)+At(998)+fr(870)+At(1145),fr(909)+At(1009)+At(1128)+fr(907),fr(950)+At(1165)+At(937)+fr(986)];
        """.trimIndent()

        /** Verbatim from `cdn.mkissa.net/…/chunks/k9WDk58N.js`, MKissa build 175, 2026-09-22. */
        val BUILD_175_SLICE = """
            function ru(){const e=["deA","bin","6mg","Jpt","abl","tot","nav","web","X1c","JVq","NXi","mCh","ode","rgl","owe","Ioz","fra","jfT","err","log","qta","aYU","dwb","qgh","zch","fFC","BVL","127","AJW","pro","L9I","sin","lpx","pto","Xoy","QEt","jzL","mqW","rkk","Ggc","kXB","Zym","VOu","enc","unc","ret","tri","ail","oCq","gin","g_m","pTW","mrj","1215590CFBiVq","str","W8=","ept","imp","1366269yYPnOR","Wz1","tRn","{}.","aa-","pHT","JCb","qTd","11554466HmnDUW","tad","o_u","rra","X8S","ild","or(","ade","tPr","ryg","hos","psC","BdT","Aao","app","061","bui","ZYT","Lfe","YJG","2128002EkcToo","GeW","cW/","rCo","Rhn","tur","xSI","urn","cry","ect","env","jcn","__p","epo","ort","Dbu","onj","war","5098835eAQpcx","Zla","eGD","MEg","tna","sig","acy","his","alh","175","txa","GVr","jjO","gAd","rMe","zSr"," (f","sub","Id=","obj","Key","toL","lan","mMO","uez","pMS","YU=","con","arC","KY=","sSc","o__","_cr","nND","pad","pty","nQX","lup","Zku","kvr","3nvnsqL","gja","sal","tio","tab","PhK","1k=","twk","tMu","HMA","Lan","exc","PXx","fro","gMu","ask","ori","Sta","tra","efi","rDf","&k=","rot","ion","cha","xrt","jzk","sSi","Hpo","joi","IRs","omi","FVC","ing","Qyj","inf",'")(',"45741312ValQgU","zAs","vRF","gth","msp","MXe","bEc","dnv","leg","att","Xor","iry","Mpk","rep","mis","YaD","DJn","len","rCa","rIJ","boo","gro","gXv","ype","-25","loc","toS","2012984LmAGDB","oOd","zFV","?bu",".0.","0.1","tAd","Ebv","ldI","SEj","UJS","UYH",'"re',"ypt","tEm","sol","tle","n t","SHA","TVK","ost","IdA","AHq","lac","isA","T0D","raw","par","map","uct","n()","ujW","uMX"];return ru=function(){return e},ru()}
            function Ei(e,t){return e=e-(-3*958+3*2624+-4624),ru()[e]}
            function Ut(e,t){return Ei(e-{_0x1f353a:662}._0x1f353a)}
            function Dt(e,t){return Ei(t- -819)}
            function p1(){const e={_0xebc9f5:235},t={VOuiu:function(n,a){return n||a}};function r(n,a){return Ut(a- -853)}return t[r(116,e._0xebc9f5)+"iu"](fd,"")}
            const fd=Dt(-274,-322),Zk=Number(101*10433119+64*-580658+1*-411782907),eT=Number(157*-333989+144928447+-6092174),hm=[Dt(-460,-418)+Dt(-277,-249)+Dt(-467,-376)+Ut(1179),Ut(1217)+Dt(-333,-405)+Dt(-500,-427)+Dt(-176,-285),Dt(-230,-319)+Dt(-463,-347)+Ut(1038)+Dt(-227,-305),Ut(1229)+Ut(1048)+Dt(-421,-307)+Ut(1101)];
            const Of={v:1,saltMul:34,saltAdd:47,fragMul:93,fragAdd:29,bootPrefix:Dt(-292,-365)+Ut(1127)+Dt(-385,-387)+":",join:"|",parts:[Ut(1128)+Dt(-117,-219)+"d",Dt(-331,-233)+"up",Dt(-442,-359)+"t",Dt(-416,-336)+"ch",Ut(1172)+"e"],omitEmptyLane:!1,envXor:9};
        """.trimIndent()
    }
}
