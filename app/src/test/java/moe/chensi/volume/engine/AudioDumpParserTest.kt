package moe.chensi.volume.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samples follow the AOSP Android 11 dump formats (ClientDescriptor.cpp / Tracks.cpp). */
class AudioDumpParserTest {
    private val policy = """
AudioPolicyManager Dump: 0x7a
 Outputs dump:
- Output 13 dump:
  ...
  AudioPolicy client dump:
  Client 1:
  - Port Id: 45 Session Id: 3417 UID: 10187
  - Format: 00000001 Sampling rate: 48000 Channels: 00000003
  - Attributes: { Content type: AUDIO_CONTENT_TYPE_MOVIE Usage: AUDIO_USAGE_MEDIA Source: AUDIO_SOURCE_DEFAULT Flags: 0x800 Tags:  }
  - Preferred Device Id: 00000000
  - State: Active
  - Stream: 3 flags: 00000000
  - Refcount: 1
  Client 2:
  - Port Id: 51 Session Id: 3521 UID: 10203
  - Format: 00000001 Sampling rate: 44100 Channels: 00000003
  - Attributes: { Content type: AUDIO_CONTENT_TYPE_SONIFICATION Usage: AUDIO_USAGE_GAME }
  - Preferred Device Id: 00000000
  - State: Inactive
  - Stream: 3 flags: 00000000
- Output 21 dump:
  Client 1:
  - Port Id: 45 Session Id: 3417 UID: 10187
  - State: Inactive
  - Stream: 3 flags: 00000000
 Inputs dump:
  Client 1:
  - Port Id: 60 Session Id: 3600 UID: 10050
  - Format: 00000001 Sampling rate: 16000 Channels: 00000010
  - Preferred Device Id: 00000000
  - State: Active
  - Source: 1 flags: 00000000
"""

    private val flinger = """
Output thread 0xe8a0 , name AudioOut_D, tid 1234, type 0 (MIXER):
  Stream volumes in dB: 0:-24, 1:-inf
  3 Tracks of which 1 are active
    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db  L dB  R dB  VS dB   Server FrmCnt  FrmRdy F Underruns  Flushed   Latency
            118    yes   6342    3417     45  A  0x000 00000001 00000003  48000  3  1  3   -12     0     0     0    00012340  15360   7680 A         0        0  101.2 k
    F1      120     no   7001    3521     51  P  0x400 00000001 00000003  44100  3 14  4     0     0     0     0    00000000    960      0 f         0        0       new
         S  121     no   7001    3521     52  S  0x000 00000001 00000003  44100  3 14  4     0     0     0     0    00000000    960      0 f         0        0       new
Input thread 0xf1 , name AudioIn_1E, tid 99, type 3 (RECORD):
  1 Tracks of which 1 are active
    Active     Id Client Session Port Id  S  Flags  Format Chn mask  SRate Source
     yes     130   5000    3600     60  A  0x000 00000001 00000010  16000      1
"""

    @Test
    fun parsesPolicyPlaybackClients() {
        val clients = AudioDumpParser.parsePolicyClients(policy)
        assertEquals(2, clients.size)
        assertEquals(3417, clients[0].sessionId)
        assertEquals(10187, clients[0].uid)
        assertEquals(true, clients[0].active)
        assertEquals(3, clients[0].stream)
        assertEquals(3521, clients[1].sessionId)
        assertEquals(false, clients[1].active)
    }

    @Test
    fun parsesFlingerPlaybackTracksOnly() {
        val tracks = AudioDumpParser.parseFlingerTracks(flinger)
        assertEquals(2, tracks.size)
        assertEquals(3417, tracks[0].sessionId)
        assertEquals(6342, tracks[0].pid)
        assertTrue(tracks[0].active)
        assertEquals(7001, tracks[1].pid)
        assertFalse(tracks[1].active)
    }

    @Test
    fun emptyDumpGivesNothing() {
        assertTrue(AudioDumpParser.parsePolicyClients("").isEmpty())
        assertTrue(AudioDumpParser.parseFlingerTracks("Permission Denial").isEmpty())
    }

    @Test
    fun volumeToDb() {
        assertEquals(0f, SessionVolumeEngine.volumeToDb(1f), 0.01f)
        assertEquals(-96f, SessionVolumeEngine.volumeToDb(0f), 0.01f)
        assertEquals(9.54f, SessionVolumeEngine.volumeToDb(3f), 0.01f)
    }
}
