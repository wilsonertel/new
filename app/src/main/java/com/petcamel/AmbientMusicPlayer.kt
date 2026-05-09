package com.petcamel

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.*

class AmbientMusicPlayer {

    @Volatile private var playing = false
    @Volatile private var paused  = false
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private val sampleRate  = 22050
    private val bufferFrames = 1024

    // Am pentatonic chord progression: Am → G → F → E  (each 12 s)
    // Each chord = [root, third/fifth, fifth/octave]
    private val chords = arrayOf(
        doubleArrayOf(110.0, 130.8, 165.0),  // Am : A2 C3 E3
        doubleArrayOf( 98.0, 123.5, 147.0),  // G  : G2 B2 D3
        doubleArrayOf( 87.3, 130.8, 174.6),  // F  : F2 A2 C3
        doubleArrayOf( 82.4, 110.0, 164.8)   // E  : E2 A2 E3
    )
    private val chordSecs = 12.0

    fun start() {
        if (playing) return
        playing = true
        paused  = false
        thread = Thread {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferFrames * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            var t     = 0.0
            val dt    = 1.0 / sampleRate
            val buf   = ShortArray(bufferFrames)

            while (playing) {
                if (paused) { Thread.sleep(50); continue }
                for (i in buf.indices) {
                    buf[i] = (generateSample(t) * Short.MAX_VALUE)
                        .toInt().coerceIn(-32767, 32767).toShort()
                    t += dt
                }
                track.write(buf, 0, buf.size)
            }

            track.stop()
            track.release()
            audioTrack = null
        }.also { it.isDaemon = true; it.start() }
    }

    private fun generateSample(t: Double): Double {
        val chordIdx = (t / chordSecs).toInt() % chords.size
        val nextIdx  = (chordIdx + 1) % chords.size
        val progress = (t % chordSecs) / chordSecs
        // crossfade between chords over last 20% of duration
        val blend = if (progress > 0.8) (progress - 0.8) / 0.2 else 0.0

        val chord = chords[chordIdx]
        val next  = chords[nextIdx]

        // Slow amplitude "breathing" — camel-pace rhythm
        val breathe = 0.55 + 0.45 * sin(t * 0.18)
        // Very subtle shimmer
        val shimmer = 0.98 + 0.02 * sin(t * 3.7)

        var sample = 0.0
        for (fi in chord.indices) {
            val f = chord[fi] * (1.0 - blend) + next[fi] * blend
            val weight = 0.45 - fi * 0.12
            // Fundamental + soft harmonics for warmth
            sample += weight * (
                0.65 * sin(2 * PI * f * t) +
                0.22 * sin(2 * PI * f * 2 * t) +
                0.09 * sin(2 * PI * f * 3 * t) +
                0.04 * sin(2 * PI * f * 0.5 * t)   // sub-octave warmth
            )
        }

        return sample * breathe * shimmer * 0.20   // master volume ~20%
    }

    fun pause() {
        paused = true
        audioTrack?.pause()
    }

    fun resume() {
        if (!playing) { start(); return }
        paused = false
        audioTrack?.play()
    }

    fun stop() {
        playing = false
        paused  = false
        try { thread?.join(600) } catch (_: InterruptedException) {}
    }
}
