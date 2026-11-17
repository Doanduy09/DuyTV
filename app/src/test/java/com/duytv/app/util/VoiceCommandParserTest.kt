package com.duytv.app.util

import com.duytv.app.data.MovieRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    private val channels = MovieRepository.getVerifiedOfficialChannels()

    @Test
    fun `test verified channel list is non-empty`() {
        assertTrue(channels.isNotEmpty())
    }

    @Test
    fun `test real Shield TV STT phonetic errors resolve to HTV7`() {
        val inputs = listOf(
            "em mở bài hát cover 7 cho anh",
            "em mở bài hát sever 7",
            "em mở bài hát sevo 7",
            "em ở đài HTV7",
            "mở bài htv7",
            "hát tôi vô bạn",
            "mở đài hát tê vê bảy"
        )
        for (raw in inputs) {
            val cmd = VoiceCommandParser.parse(raw, channels)
            assertTrue("Expected SwitchChannel for '$raw' but got $cmd", cmd is VoiceCommand.SwitchChannel)
            val ch = (cmd as VoiceCommand.SwitchChannel).channel
            assertTrue("Expected HTV7 for '$raw' but got ${ch.name}", ch.name.contains("HTV7", ignoreCase = true))
        }
    }

    @Test
    fun `test regional and flagship channels without numbers`() {
        val vtvCmd = VoiceCommandParser.parse("mở vtv", channels) as VoiceCommand.SwitchChannel
        assertTrue(vtvCmd.channel.name.startsWith("VTV1", ignoreCase = true))

        val htvCmd = VoiceCommandParser.parse("bật htv", channels) as VoiceCommand.SwitchChannel
        assertTrue(htvCmd.channel.name.startsWith("HTV7", ignoreCase = true))

        val thvlCmd = VoiceCommandParser.parse("mở đài vĩnh long cho anh", channels) as VoiceCommand.SwitchChannel
        assertTrue(thvlCmd.channel.name.contains("THVL1", ignoreCase = true))

        val dtCmd = VoiceCommandParser.parse("mở đài đồng tháp", channels) as VoiceCommand.SwitchChannel
        assertTrue(dtCmd.channel.name.contains("Đồng Tháp", ignoreCase = true) || dtCmd.channel.name.contains("THĐT", ignoreCase = true))

        val thdtCmd = VoiceCommandParser.parse("xem thdt", channels) as VoiceCommand.SwitchChannel
        assertTrue(thdtCmd.channel.name.contains("Đồng Tháp", ignoreCase = true) || thdtCmd.channel.name.contains("THĐT", ignoreCase = true))
    }

    @Test
    fun `test general TV command never falls back to Unknown or YouTube`() {
        val genericCommands = listOf("mở đài", "em ở đài", "xem đài", "bật tivi", "mở truyền hình")
        for (raw in genericCommands) {
            val cmd = VoiceCommandParser.parse(raw, channels)
            assertTrue("Expected SwitchChannel for '$raw' but got $cmd", cmd is VoiceCommand.SwitchChannel)
        }
    }

    @Test
    fun `test lattice candidate scoring prefers TV channel over music hallucination`() {
        val candidates = listOf(
            "em mở bài hát Bolero 7 cho anh",
            "em mở bài hát cover 7 cho anh"
        )
        val best = VoiceCommandParser.selectBestCandidate(candidates, channels)
        val cmd = VoiceCommandParser.parse(best, channels)
        assertTrue(cmd is VoiceCommand.SwitchChannel)
        assertTrue((cmd as VoiceCommand.SwitchChannel).channel.name.contains("HTV7", ignoreCase = true))

        val candidates2 = listOf("Em ở khách", "em ở đài", "em mở đài")
        val best2 = VoiceCommandParser.selectBestCandidate(candidates2, channels)
        val cmd2 = VoiceCommandParser.parse(best2, channels)
        assertTrue(cmd2 is VoiceCommand.SwitchChannel)
    }

    @Test
    fun `test playback volume seek speed episode and app commands`() {
        assertEquals(VoiceCommand.AdjustVolume(true), VoiceCommandParser.parse("tăng âm lượng lên", channels))
        assertEquals(VoiceCommand.AdjustVolume(false), VoiceCommandParser.parse("giảm âm lượng", channels))
        assertEquals(VoiceCommand.ToggleMute, VoiceCommandParser.parse("tắt tiếng", channels))
        assertEquals(VoiceCommand.SkipIntro, VoiceCommandParser.parse("bỏ qua mở đầu", channels))
        assertEquals(VoiceCommand.ToggleClearDialog, VoiceCommandParser.parse("rõ lời thoại", channels))

        val ep = VoiceCommandParser.parse("mở tập 12", channels)
        assertEquals(VoiceCommand.SwitchEpisode(12), ep)

        val seek = VoiceCommandParser.parse("tua tới 5 phút", channels)
        assertTrue(seek is VoiceCommand.SeekDelta && seek.deltaSeconds == 300)

        val yt = VoiceCommandParser.parse("mở nhạc Lệ Quyên trên YouTube", channels)
        assertTrue(yt is VoiceCommand.YouTubeSearch)
        assertFalse((yt as VoiceCommand.YouTubeSearch).query.contains("youtube", ignoreCase = true))

        val movie = VoiceCommandParser.parse("mở phim Châu Tinh Trì", channels)
        assertTrue(movie is VoiceCommand.OpenMovie)
        assertNotNull(movie)
    }

    @Test
    fun testSmartUtilitiesVoiceCommands() {
        val sleepCmd = VoiceCommandParser.parse("hẹn giờ tắt 30 phút", channels)
        assertTrue(sleepCmd is VoiceCommand.SetSleepTimer)
        assertEquals(30, (sleepCmd as VoiceCommand.SetSleepTimer).minutes)

        val cancelSleepCmd = VoiceCommandParser.parse("tắt hẹn giờ", channels)
        assertTrue(cancelSleepCmd is VoiceCommand.SetSleepTimer)
        assertEquals(0, (cancelSleepCmd as VoiceCommand.SetSleepTimer).minutes)

        val nightCmd = VoiceCommandParser.parse("bật chế độ dịu mắt ban đêm", channels)
        assertTrue(nightCmd is VoiceCommand.ToggleNightMode)

        val screenOffCmd = VoiceCommandParser.parse("tắt màn hình chỉ nghe tiếng", channels)
        assertTrue(screenOffCmd is VoiceCommand.ActivateScreenOffAudio)

        val lockCmd = VoiceCommandParser.parse("khóa trẻ em", channels)
        assertTrue(lockCmd is VoiceCommand.ToggleTouchLock)

        val pipCmd = VoiceCommandParser.parse("thu nhỏ màn hình", channels)
        assertTrue(pipCmd is VoiceCommand.EnterPip)

        val filterCmd = VoiceCommandParser.parse("đổi bộ lọc điện ảnh", channels)
        assertTrue(filterCmd is VoiceCommand.CycleCinematicFilter)

        val diagCmd = VoiceCommandParser.parse("kiểm tra thông số luồng", channels)
        assertTrue(diagCmd is VoiceCommand.ShowDiagnostics)

        val favCatCmd = VoiceCommandParser.parse("mở phim yêu thích", channels)
        assertTrue(favCatCmd is VoiceCommand.BrowseCategory)
        assertEquals("fav-movies", (favCatCmd as VoiceCommand.BrowseCategory).categoryId)
    }

    @Test
    fun `test universal TV control and accessibility commands`() {
        val volPct = VoiceCommandParser.parse("đặt âm lượng 40%", channels)
        assertTrue(volPct is VoiceCommand.SetVolumePercent)
        assertEquals(40, (volPct as VoiceCommand.SetVolumePercent).percent)

        val followUpVol = VoiceCommandParser.parse("em tăng lên 80% cho anh", channels)
        assertTrue(followUpVol is VoiceCommand.SetVolumePercent)
        assertEquals(80, (followUpVol as VoiceCommand.SetVolumePercent).percent)

        val hdmi = VoiceCommandParser.parse("chuyển sang hdmi 1", channels)
        assertTrue(hdmi is VoiceCommand.SwitchHdmiInput)
        assertEquals("hdmi 1", (hdmi as VoiceCommand.SwitchHdmiInput).inputQuery)

        val nextMedia = VoiceCommandParser.parse("qua bài", channels)
        assertTrue(nextMedia is VoiceCommand.GlobalMediaControl)
        assertEquals(VoiceCommand.MediaKeyAction.NEXT, (nextMedia as VoiceCommand.GlobalMediaControl).action)

        val clickUi = VoiceCommandParser.parse("bấm vào bỏ qua quảng cáo", channels)
        assertTrue(clickUi is VoiceCommand.UiClickText)
        assertEquals("bỏ qua quảng cáo", (clickUi as VoiceCommand.UiClickText).targetText)

        val scrollDown = VoiceCommandParser.parse("cuộn xuống", channels)
        assertTrue(scrollDown is VoiceCommand.UiScroll)
        assertTrue((scrollDown as VoiceCommand.UiScroll).forward)

        val recents = VoiceCommandParser.parse("mở đa nhiệm", channels)
        assertTrue(recents is VoiceCommand.SystemAction)
        assertEquals(VoiceCommand.SystemActionType.RECENTS, (recents as VoiceCommand.SystemAction).type)

        val screenshot = VoiceCommandParser.parse("chụp màn hình", channels)
        assertTrue(screenshot is VoiceCommand.SystemAction)
        assertEquals(VoiceCommand.SystemActionType.SCREENSHOT, (screenshot as VoiceCommand.SystemAction).type)
    }

    @Test
    fun `test GeminiVoiceClient multi-action JSON parsing`() {
        val json = """
            ```json
            {
              "action": "system_action",
              "tts_reply": "Dạ, em giảm âm lượng 30% rồi mở VTV1 cho anh",
              "actions": [
                {"action": "set_volume_percent", "target": "30"},
                {"action": "play_tv", "query": "VTV1"}
              ]
            }
            ```
        """.trimIndent()
        val parsed = GeminiVoiceClient.parseResponseJson(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.actions.size)
        assertEquals("set_volume_percent", parsed.actions[0].action)
        assertEquals("30", parsed.actions[0].target)
        assertEquals("play_tv", parsed.actions[1].action)
        assertEquals("VTV1", parsed.actions[1].query)
    }

    @Test
    fun `test movie category commands do not hijack into TV channels`() {
        val channelsWithMovieChannel = channels + com.duytv.app.data.TvChannel(
            id = "test_phim",
            name = "Phim Hay TV",
            streamUrl = "https://example.com/live.m3u8"
        )
        val phimLe = VoiceCommandParser.parse("mở phim lẻ", channelsWithMovieChannel)
        assertTrue("Expected BrowseCategory for 'mở phim lẻ' but got $phimLe", phimLe is VoiceCommand.BrowseCategory)
        assertEquals("phim-le", (phimLe as VoiceCommand.BrowseCategory).categoryId)

        val phimBo = VoiceCommandParser.parse("mở phim bộ", channelsWithMovieChannel)
        assertTrue("Expected BrowseCategory for 'mở phim bộ' but got $phimBo", phimBo is VoiceCommand.BrowseCategory)
        assertEquals("phim-bo", (phimBo as VoiceCommand.BrowseCategory).categoryId)

        val phimTm = VoiceCommandParser.parse("mở phim bộ thuyết minh", channelsWithMovieChannel)
        assertTrue("Expected BrowseCategory for 'mở phim bộ thuyết minh' but got $phimTm", phimTm is VoiceCommand.BrowseCategory)
        assertEquals("phim-thuyet-minh", (phimTm as VoiceCommand.BrowseCategory).categoryId)

        val footballAsk = VoiceCommandParser.parse("hãy xem cho anh có bóng đá không", channelsWithMovieChannel)
        assertTrue("Expected AskFootballAdvisor but got $footballAsk", footballAsk is VoiceCommand.AskFootballAdvisor)
    }
}
