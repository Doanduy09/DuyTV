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
}
