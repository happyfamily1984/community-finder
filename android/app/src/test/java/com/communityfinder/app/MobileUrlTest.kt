package com.communityfinder.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileUrlTest {
    private fun p(source: String, url: String) = Post("$source:$url", source, "t", url, null, null, null, 0, 0, 0)

    @Test
    fun convertsToMobilePages() {
        // 실제 수집된 주소 형식 기준 (GitHub 공개 코드/링크로 모바일 주소 규칙 확인)
        assertEquals("https://m.dcinside.com/board/dcbest/478512", mobileUrl(p("dcinside", "https://gall.dcinside.com/board/view/?id=dcbest&no=478512")))
        assertEquals("https://m.ruliweb.com/best/board/300143/read/77380399", mobileUrl(p("ruliweb", "https://bbs.ruliweb.com/best/board/300143/read/77380399")))
        assertEquals("https://m.clien.net/service/board/park/19291831", mobileUrl(p("clien", "https://www.clien.net/service/board/park/19291831")))
        assertEquals("https://pann.nate.com/talk/375716356", mobileUrl(p("natepann", "https://pann.nate.com/talk/375716356")))
        assertEquals("https://m.bobaedream.co.kr/board/bbs_view/best/1047358", mobileUrl(p("bobaedream", "https://www.bobaedream.co.kr/view?code=best&No=1047358")))
        assertEquals("https://m.fmkorea.com/8839201234", mobileUrl(p("fmkorea", "https://www.fmkorea.com/8839201234")))
        assertEquals("https://theqoo.net/hot/4405266450", mobileUrl(p("theqoo", "https://theqoo.net/hot/4405266450")))
    }
}
